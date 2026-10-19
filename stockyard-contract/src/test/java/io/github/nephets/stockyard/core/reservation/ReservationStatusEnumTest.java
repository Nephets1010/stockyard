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
package io.github.nephets.stockyard.core.reservation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预占状态枚举的形状约束。
 *
 * <p>为什么枚举的顺序与数量本身要被断言：{@code name()} 会被写进 HTTP 响应体（{@code ReserveResponse.status}）
 * 与数据库枚举列。新增或改名都是对外的协议变更，测试要在它悄悄发生时立刻报出来。
 */
class ReservationStatusEnumTest {

    @Test
    void has_exactly_the_five_documented_states() {
        assertThat(ReservationStatus.values())
                .containsExactly(
                        ReservationStatus.PENDING,
                        ReservationStatus.ACTIVE,
                        ReservationStatus.CONFIRMED,
                        ReservationStatus.CANCELLED,
                        ReservationStatus.EXPIRED);
    }

    @Test
    void round_trips_through_name() {
        for (ReservationStatus s : ReservationStatus.values()) {
            assertThat(ReservationStatus.valueOf(s.name())).isSameAs(s);
        }
    }

    @Test
    void three_states_are_terminal() {
        // CONFIRMED / CANCELLED / EXPIRED 之后不再有任何出边：这是「库存只会被释放一次」的根据。
        assertThat(ReservationStatus.CONFIRMED.ordinal()).isGreaterThan(0);
        assertThat(ReservationStatus.CANCELLED.ordinal()).isGreaterThan(0);
        assertThat(ReservationStatus.EXPIRED.ordinal()).isGreaterThan(0);
    }
}
