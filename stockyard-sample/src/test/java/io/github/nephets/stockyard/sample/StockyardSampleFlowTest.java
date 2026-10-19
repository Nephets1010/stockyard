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
package io.github.nephets.stockyard.sample;

import io.github.nephets.stockyard.core.engine.StockyardEngine;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.starter.StockyardProperties;
import io.github.nephets.stockyard.starter.StockyardTenantFilter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 示例应用的端到端冒烟：自动装配真的把整套东西装起来了，且 HTTP 全链路可用。
 *
 * <p>为什么示例也要跑 HTTP 而不是只断言「上下文起得来」：{@code contextLoads} 只能证明 Bean 之间
 * 不打架，证明不了过滤器、控制器、引擎这三层真的串起来。这里补的是「从 HTTP 进来的请求能一路走到 库存水位」这条链路。
 *
 * <p>注意：{@code StockyardDemo} 在启动时会占用 {@code SKU-1 / WH-1}，因此本测试一律使用 {@code SMOKE-*} 这类独立
 * SKU，避免和演示数据互相干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockyardSampleFlowTest {

    @Autowired MockMvc mvc;
    @Autowired StockyardEngine engine;
    @Autowired StockyardProperties properties;

    @Test
    void the_starter_wired_up_the_whole_stack() {
        assertThat(engine).isNotNull();
        assertThat(properties.getTenantHeader()).isEqualTo("X-Tenant-Id");
        assertThat(new StockyardTenantFilter(properties)).isNotNull();
    }

    @Test
    void the_engine_received_a_clock_from_the_context() {
        // 时钟 Bean 若没装配成功，engine.clock() 会是 null，所有 TTL 判定都会 NPE。
        assertThat(engine.clock()).isNotNull();
        assertThat(engine.clock()).isInstanceOf(Clock.class);
    }

    @Test
    void reserve_confirm_deduct_works_end_to_end_over_http() throws Exception {
        Sku sku = Sku.of("SMOKE-1");
        Warehouse wh = Warehouse.of("SMOKE-WH");
        engine.stockIn(sku, wh, 50);

        String json =
                mvc.perform(
                                post("/stockyard/reservations")
                                        .header("X-Tenant-Id", "smoke-tenant")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"sku\":\"SMOKE-1\",\"warehouse\":\"SMOKE-WH\",\"qty\":20}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.available").value(30))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id = extract(json);

        mvc.perform(post("/stockyard/reservations/" + id + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.available").value(30));
    }

    @Test
    void a_replayed_deduction_does_not_move_the_stock_over_http() throws Exception {
        Sku sku = Sku.of("SMOKE-2");
        Warehouse wh = Warehouse.of("SMOKE-WH");
        engine.stockIn(sku, wh, 40);
        String body =
                "{\"requestId\":\"SMOKE-ORDER-1\",\"sku\":\"SMOKE-2\",\"warehouse\":\"SMOKE-WH\",\"qty\":9}";

        mvc.perform(
                        post("/stockyard/deductions")
                                .header("X-Tenant-Id", "smoke-tenant")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.available").value(31));
        // 重放第二次：库存必须纹丝不动。
        mvc.perform(
                        post("/stockyard/deductions")
                                .header("X-Tenant-Id", "smoke-tenant")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.available").value(31));
    }

    @Test
    void the_position_endpoint_reports_a_consistent_water_level() throws Exception {
        Sku sku = Sku.of("SMOKE-3");
        Warehouse wh = Warehouse.of("SMOKE-WH");
        engine.stockIn(sku, wh, 12);
        engine.reserve(
                "SMOKE-R1",
                io.github.nephets.stockyard.core.StockyardTenant.of("smoke-tenant"),
                sku,
                wh,
                5,
                null);

        mvc.perform(
                        get("/stockyard/positions")
                                .param("sku", "SMOKE-3")
                                .param("warehouse", "SMOKE-WH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(12))
                .andExpect(jsonPath("$.allocated").value(5))
                .andExpect(jsonPath("$.available").value(7));
    }

    @Test
    void a_request_without_the_tenant_header_is_refused_with_400() throws Exception {
        // 缺租户头必须被明确拒绝，而不是退化成「默认租户」——后者会让两个租户的库存互相覆盖。
        mvc.perform(
                        post("/stockyard/reservations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"sku\":\"SMOKE-1\",\"warehouse\":\"SMOKE-WH\",\"qty\":1}"))
                .andExpect(status().isBadRequest());
    }

    private static String extract(String json) {
        String key = "\"reservationId\":\"";
        int start = json.indexOf(key) + key.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
