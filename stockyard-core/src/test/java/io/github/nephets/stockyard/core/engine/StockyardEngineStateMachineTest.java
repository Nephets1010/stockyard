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

import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.reservation.ReservationStatus;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引擎层的状态机流转与「库存跟着状态走」的一致性。
 *
 * <p>值对象层已经穷举了迁移矩阵（见 contract 的 {@code ReservationStateMachineTest}）；这里盯的是
 * 另一半：<strong>每一次状态迁移都必须伴随着正确的库存动作</strong>。状态对了但库存没动，是更隐蔽的 一类错误——它不会报错，只会在对账时表现为账实不符。
 */
class StockyardEngineStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private static StockyardEngine newEngine() {
        return new StockyardEngine(
                new InMemoryReservationStore(),
                new InMemoryStockPositionStore(),
                new InMemoryDeductionStore(),
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void reserve_moves_stock_from_available_to_allocated() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, null);
        // 预占只挪动 allocated，不动 onHand：货还在仓库里，只是不可再卖。
        assertThat(e.position(sku, wh).onHand()).isEqualTo(10);
        assertThat(e.position(sku, wh).allocated()).isEqualTo(4);
        assertThat(e.position(sku, wh).available()).isEqualTo(6);
    }

    @Test
    void confirm_moves_stock_out_of_the_warehouse() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        String id = e.reserve("R1", tenant, sku, wh, 4, null).id();
        assertThat(e.confirm(id).status()).isEqualTo(ReservationStatus.CONFIRMED);
        // 确认 = 出库：onHand 与 allocated 同时减少，可用量不变。
        assertThat(e.position(sku, wh).onHand()).isEqualTo(6);
        assertThat(e.position(sku, wh).allocated()).isZero();
        assertThat(e.position(sku, wh).available()).isEqualTo(6);
    }

    @Test
    void cancel_returns_stock_to_the_available_pool() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        String id = e.reserve("R1", tenant, sku, wh, 4, null).id();
        assertThat(e.cancel(id, "用户取消").status()).isEqualTo(ReservationStatus.CANCELLED);
        // 取消 = 把货还给可卖池：onHand 不变，allocated 归还。
        assertThat(e.position(sku, wh).onHand()).isEqualTo(10);
        assertThat(e.position(sku, wh).allocated()).isZero();
        assertThat(e.position(sku, wh).available()).isEqualTo(10);
    }

    @Test
    void expiry_returns_stock_to_the_available_pool() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, 60L);
        assertThat(e.expireDue(T0.plusSeconds(61))).containsExactly("R1");
        assertThat(e.position(sku, wh).onHand()).isEqualTo(10);
        assertThat(e.position(sku, wh).allocated()).isZero();
        assertThat(e.position(sku, wh).available()).isEqualTo(10);
    }

    @Test
    void a_terminal_reservation_is_never_touched_by_a_later_expiry_sweep() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        String id = e.reserve("R1", tenant, sku, wh, 4, 60L).id();
        e.confirm(id);
        assertThat(e.expireDue(T0.plusSeconds(3600))).isEmpty();
        // 已确认的预占不能被回收，否则库存会被释放两次（凭空造货）。
        assertThat(e.position(sku, wh).onHand()).isEqualTo(6);
        assertThat(e.position(sku, wh).allocated()).isZero();
    }

    @Test
    void expiry_sweep_returns_every_due_reservation_and_is_idempotent() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 100);
        e.reserve("R1", tenant, sku, wh, 10, 60L);
        e.reserve("R2", tenant, sku, wh, 20, 60L);
        e.reserve("R3", tenant, sku, wh, 30, 600L); // 还没到期
        Instant due = T0.plusSeconds(61);
        assertThat(e.expireDue(due)).containsExactlyInAnyOrder("R1", "R2");
        // 再扫一次：已经 EXPIRED 的不会被重复回收。
        assertThat(e.expireDue(due)).isEmpty();
        assertThat(e.position(sku, wh).allocated()).isEqualTo(30);
        assertThat(e.position(sku, wh).available()).isEqualTo(70);
    }

    @Test
    void stock_can_be_replenished_after_it_has_been_fully_sold() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 5);
        String id = e.reserve("R1", tenant, sku, wh, 5, null).id();
        e.confirm(id);
        assertThat(e.position(sku, wh).available()).isZero();
        e.stockIn(sku, wh, 5);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(5);
        assertThat(e.position(sku, wh).available()).isEqualTo(5);
    }

    @Test
    void a_reservation_that_was_cancelled_can_be_replaced_by_a_new_one() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 10, null);
        e.cancel("R1", "x");
        // 取消释放的库存必须立刻可再卖，否则「少卖」。
        var r2 = e.reserve("R2", tenant, sku, wh, 10, null);
        assertThat(r2.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(e.position(sku, wh).allocated()).isEqualTo(10);
    }
}
