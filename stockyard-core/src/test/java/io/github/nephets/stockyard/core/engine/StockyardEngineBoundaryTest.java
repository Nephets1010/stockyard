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
package io.github.nephets.stockyard.core.engine;

import io.github.nephets.stockyard.core.OversellException;
import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.reservation.ReservationException;
import io.github.nephets.stockyard.core.reservation.ReservationNotFoundException;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引擎入口的边界与非法输入。
 *
 * <p>为什么 null 必须在入口就被 {@code requireNonNull} 拦住：库存系统里一个 null 如果混进 key （sku / warehouse /
 * tenant），它不会立刻报错，而是变成一条 key 为 null 的水位——然后被别的 SKU 查出来。缺陷越晚暴露，现场越少。
 */
class StockyardEngineBoundaryTest {

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private static StockyardEngine newEngine() {
        return new StockyardEngine(
                new InMemoryReservationStore(),
                new InMemoryStockPositionStore(),
                new InMemoryDeductionStore());
    }

    @Test
    void stock_in_of_zero_is_allowed() {
        StockyardEngine e = newEngine();
        assertThat(e.stockIn(sku, wh, 0).onHand()).isZero();
    }

    @Test
    void stock_in_of_a_negative_amount_is_rejected() {
        assertThatThrownBy(() -> newEngine().stockIn(sku, wh, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserve_of_zero_is_rejected() {
        // qty=0 的预占是一条永远不会被确认、也永远不会释放库存的脏数据。
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        assertThatThrownBy(() -> e.reserve("R1", tenant, sku, wh, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserve_of_a_negative_amount_is_rejected() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        assertThatThrownBy(() -> e.reserve("R1", tenant, sku, wh, -5, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserve_beyond_the_available_stock_throws_oversell() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        assertThatThrownBy(() -> e.reserve("R1", tenant, sku, wh, 11, null))
                .isInstanceOf(OversellException.class)
                .hasMessageContaining("库存不足");
        // 被拒绝的预占不能留下半条水位。
        assertThat(e.position(sku, wh).allocated()).isZero();
        assertThat(e.position(sku, wh).available()).isEqualTo(10);
    }

    @Test
    void reserve_exactly_the_available_stock_is_allowed() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        assertThat(e.reserve("R1", tenant, sku, wh, 10, null).status()).isNotNull();
        assertThat(e.position(sku, wh).available()).isZero();
    }

    @Test
    void an_unknown_sku_has_zero_stock_so_any_reserve_is_refused() {
        // 未知 SKU 不应该 NPE，而应该表现为「库存为 0，预占被拒」。
        StockyardEngine e = newEngine();
        Sku unknown = Sku.of("NEVER-STOCKED");
        assertThat(e.position(unknown, wh).onHand()).isZero();
        assertThatThrownBy(() -> e.reserve("R1", tenant, unknown, wh, 1, null))
                .isInstanceOf(OversellException.class);
    }

    @Test
    void an_absurdly_large_reserve_is_refused_instead_of_overflowing() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        assertThatThrownBy(() -> e.reserve("R1", tenant, sku, wh, Long.MAX_VALUE, null))
                .isInstanceOf(OversellException.class);
        assertThat(e.position(sku, wh).available()).isEqualTo(10);
    }

    @Test
    void null_reserve_arguments_are_rejected() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        assertThatThrownBy(() -> e.reserve(null, tenant, sku, wh, 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> e.reserve("R1", null, sku, wh, 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> e.reserve("R1", tenant, null, wh, 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> e.reserve("R1", tenant, sku, null, 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_deduct_arguments_are_rejected() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        assertThatThrownBy(() -> e.deduct(null, tenant, sku, wh, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> e.deduct("D1", null, sku, wh, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> e.deduct("D1", tenant, null, wh, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> e.deduct("D1", tenant, sku, null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void confirm_of_an_unknown_reservation_is_reported_not_swallowed() {
        // 缺失必须抛明确的领域异常，而不是静默返回 null——否则调用方会以为确认成功了。
        assertThatThrownBy(() -> newEngine().confirm("NOPE"))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> newEngine().cancel("NOPE", "x"))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void cancelling_an_already_confirmed_reservation_is_rejected() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        String id = e.reserve("R1", tenant, sku, wh, 4, null).id();
        e.confirm(id);
        assertThatThrownBy(() -> e.cancel(id, "late cancel"))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("已确认不可取消");
        // 被拒绝的取消不能把库存释放掉。
        assertThat(e.position(sku, wh).onHand()).isEqualTo(6);
    }

    @Test
    void confirming_an_already_cancelled_reservation_is_rejected() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        String id = e.reserve("R1", tenant, sku, wh, 4, null).id();
        e.cancel(id, "x");
        assertThatThrownBy(() -> e.confirm(id)).isInstanceOf(ReservationException.class);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(10);
    }

    @Test
    void the_same_sku_in_two_warehouses_has_independent_stock() {
        StockyardEngine e = newEngine();
        Warehouse wh2 = Warehouse.of("W2");
        e.stockIn(sku, wh, 10);
        e.stockIn(sku, wh2, 3);
        e.reserve("R1", tenant, sku, wh, 10, null);
        assertThat(e.position(sku, wh).available()).isZero();
        assertThat(e.position(sku, wh2).available()).isEqualTo(3);
    }
}
