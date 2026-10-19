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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 请求/响应载体的形状。
 *
 * <p>为什么 DTO 也要测：这些 record 的组件名就是 JSON 字段名（HTTP 协议的一部分）。改名、改顺序、 把 {@code ttlSeconds} 从 {@code Long}
 * 改成 {@code long}（那样「不过期」就没法表达了）都会破坏 外部调用方，而这种破坏在没有断言时是完全静默的。
 */
class StockyardDtosTest {

    @Test
    void reserve_request_keeps_ttl_optional() {
        // ttlSeconds 必须是包装类型：null 表示「不过期」，改成基本类型 long 就会退化成 0（立刻过期）。
        assertThat(new ReserveRequest("S", "W", 1, null).ttlSeconds()).isNull();
        assertThat(new ReserveRequest("S", "W", 1, 60L).ttlSeconds()).isEqualTo(60L);
    }

    @Test
    void reserve_request_carries_the_business_fields() {
        ReserveRequest r = new ReserveRequest("SKU-1", "WH-1", 5, 30L);
        assertThat(r.sku()).isEqualTo("SKU-1");
        assertThat(r.warehouse()).isEqualTo("WH-1");
        assertThat(r.qty()).isEqualTo(5);
    }

    @Test
    void reserve_response_reports_the_state_and_the_remaining_stock() {
        ReserveResponse r = new ReserveResponse("R1", "ACTIVE", 7);
        assertThat(r.reservationId()).isEqualTo("R1");
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(r.available()).isEqualTo(7);
    }

    @Test
    void cancel_request_allows_a_missing_reason() {
        assertThat(new CancelRequest(null).reason()).isNull();
        assertThat(new CancelRequest("用户取消").reason()).isEqualTo("用户取消");
    }

    @Test
    void deduct_request_carries_the_idempotency_key() {
        DeductRequest d = new DeductRequest("ORDER-1", "S", "W", 3);
        assertThat(d.requestId()).isEqualTo("ORDER-1");
        assertThat(d.qty()).isEqualTo(3);
    }

    @Test
    void deduct_response_reports_the_outcome() {
        DeductResponse d = new DeductResponse("ORDER-1", "FAILED", 0);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.available()).isZero();
    }

    @Test
    void position_response_exposes_the_whole_water_level() {
        PositionResponse p = new PositionResponse("S", "W", 10, 3, 7);
        assertThat(p.onHand()).isEqualTo(10);
        assertThat(p.allocated()).isEqualTo(3);
        assertThat(p.available()).isEqualTo(7);
        // 响应里同时给出三个量：调用方可以用 onHand - allocated == available 自行核对，
        // 一旦水位被算错，这个等式会立刻不成立。
        assertThat(p.onHand() - p.allocated()).isEqualTo(p.available());
    }

    @Test
    void dtos_have_value_equality() {
        assertThat(new ReserveRequest("S", "W", 1, null))
                .isEqualTo(new ReserveRequest("S", "W", 1, null));
        assertThat(new DeductRequest("D", "S", "W", 1))
                .isEqualTo(new DeductRequest("D", "S", "W", 1));
        assertThat(new PositionResponse("S", "W", 1, 0, 1))
                .isNotEqualTo(new PositionResponse("S", "W", 1, 1, 0));
    }
}
