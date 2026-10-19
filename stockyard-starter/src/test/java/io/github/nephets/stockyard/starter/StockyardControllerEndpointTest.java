/*
 * Copyright 2026 The stockyard Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nephets.stockyard.starter;

import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.engine.StockyardEngine;
import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端点的端到端行为（真实引擎 + 真实控制器，不 mock）。
 *
 * <p>为什么还要跑一遍非 mock 的：{@code StockyardControllerTest} 用的是 {@code @MockBean} 引擎， 它能证明「HTTP
 * 参数被正确传递」，但证明不了「这一整套真的能跑通」。这里用真实引擎把 预占 → 确认 / 取消 / 超时 / 幂等扣减四条链路各跑一遍，覆盖的是集成层面的回归。
 *
 * <p>时钟用可手动推进的实现：这样「等待 TTL 到期」是一次 {@code advanceSeconds(61)}，而不是 {@code Thread.sleep}。
 */
class StockyardControllerEndpointTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** 可手动推进的时钟，用来让 TTL 过期成为确定性事件。 */
    static final class MutableClock extends Clock {
        private Instant now = T0;

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MockMvc mvc;
    private MutableClock clock;
    private Sku sku;
    private Warehouse wh;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        StockyardEngine engine =
                new StockyardEngine(
                        new InMemoryReservationStore(),
                        new InMemoryStockPositionStore(),
                        new InMemoryDeductionStore(),
                        clock);
        mvc = MockMvcBuilders.standaloneSetup(new StockyardController(engine)).build();
        sku = Sku.of("S");
        wh = Warehouse.of("W");
        engine.stockIn(sku, wh, 100);
        StockyardTenantContext.set(StockyardTenant.of("acme"));
    }

    @AfterEach
    void cleanup() {
        StockyardTenantContext.clear();
    }

    private String reserve(long qty, Long ttlSeconds) throws Exception {
        String body =
                ttlSeconds == null
                        ? "{\"sku\":\"S\",\"warehouse\":\"W\",\"qty\":" + qty + "}"
                        : "{\"sku\":\"S\",\"warehouse\":\"W\",\"qty\":"
                                + qty
                                + ",\"ttlSeconds\":"
                                + ttlSeconds
                                + "}";
        String json =
                mvc.perform(
                                post("/stockyard/reservations")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return extractReservationId(json);
    }

    private static String extractReservationId(String json) {
        String key = "\"reservationId\":\"";
        int start = json.indexOf(key) + key.length();
        return json.substring(start, json.indexOf('"', start));
    }

    @Test
    void reserve_then_confirm_moves_the_reservation_to_confirmed() throws Exception {
        String id = reserve(10, null);
        mvc.perform(post("/stockyard/reservations/" + id + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.available").value(90));
    }

    @Test
    void reserve_then_cancel_returns_the_stock_to_the_pool() throws Exception {
        String id = reserve(10, null);
        mvc.perform(
                        post("/stockyard/reservations/" + id + "/cancel")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"用户取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.available").value(100));
    }

    @Test
    void reserve_then_expire_releases_the_stock() throws Exception {
        reserve(10, 60L);
        clock.advanceSeconds(61);
        mvc.perform(post("/stockyard/reservations/expire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").exists());
        mvc.perform(get("/stockyard/positions").param("sku", "S").param("warehouse", "W"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocated").value(0))
                .andExpect(jsonPath("$.available").value(100));
    }

    @Test
    void deducting_the_same_request_id_twice_only_deducts_once() throws Exception {
        String body = "{\"requestId\":\"ORDER-1\",\"sku\":\"S\",\"warehouse\":\"W\",\"qty\":7}";
        mvc.perform(
                        post("/stockyard/deductions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.available").value(93));
        // 重放：状态仍是 SUCCESS（返回的是首次结论），但库存不能再掉。
        mvc.perform(
                        post("/stockyard/deductions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.available").value(93));
    }

    @Test
    void deducting_more_than_the_stock_returns_a_failed_record() throws Exception {
        mvc.perform(
                        post("/stockyard/deductions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"requestId\":\"ORDER-2\",\"sku\":\"S\",\"warehouse\":\"W\",\"qty\":101}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.available").value(100));
    }

    @Test
    void the_position_endpoint_reports_the_whole_water_level() throws Exception {
        reserve(30, null);
        mvc.perform(get("/stockyard/positions").param("sku", "S").param("warehouse", "W"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(100))
                .andExpect(jsonPath("$.allocated").value(30))
                .andExpect(jsonPath("$.available").value(70));
    }

    @Test
    void confirm_of_an_unknown_reservation_is_never_silently_accepted() {
        // 关键不是「返回 500」这个具体状态码，而是它绝不能被吞成 200——200 会让调用方以为确认成功了。
        assertThatThrownBy(
                        () -> mvc.perform(post("/stockyard/reservations/DOES-NOT-EXIST/confirm")))
                .isInstanceOf(Exception.class);
    }
}
