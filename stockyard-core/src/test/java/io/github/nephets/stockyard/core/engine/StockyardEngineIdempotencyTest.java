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
import io.github.nephets.stockyard.core.idempotency.DeductionStatus;
import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.reservation.ReservationConflictException;
import io.github.nephets.stockyard.core.reservation.ReservationStatus;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等语义的全部口径。
 *
 * <p>为什么幂等要被反复钉住：库存扣减由「支付成功」这类消息触发，而消息中间件只保证至少一次投递。
 * 幂等失效的表现是<strong>资损</strong>——同一笔订单被扣两次库存，而且它在功能测试里永远测不出来。
 *
 * <p>幂等的粒度由调用方决定：同一个 requestId 重发 N 次只扣 1 次，N 个不同 requestId 各扣 1 次。
 */
class StockyardEngineIdempotencyTest {

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
    void the_same_request_id_is_only_deducted_once() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 100);
        for (int i = 0; i < 10; i++) {
            assertThat(e.deduct("ORDER-1", tenant, sku, wh, 7).status())
                    .isEqualTo(DeductionStatus.SUCCESS);
        }
        // 10 次提交，库存只掉 7。
        assertThat(e.position(sku, wh).onHand()).isEqualTo(93);
        assertThat(e.position(sku, wh).available()).isEqualTo(93);
    }

    @Test
    void repeated_deductions_return_the_very_first_record() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 100);
        var first = e.deduct("ORDER-1", tenant, sku, wh, 7);
        var again = e.deduct("ORDER-1", tenant, sku, wh, 7);
        // 必须返回同一个对象：调用方据此判断「这次是重放」。
        assertThat(again).isSameAs(first);
        assertThat(again.createdAt()).isEqualTo(first.createdAt());
    }

    @Test
    void different_request_ids_are_each_deducted_once() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 100);
        for (int i = 0; i < 5; i++) {
            e.deduct("ORDER-" + i, tenant, sku, wh, 7);
        }
        assertThat(e.position(sku, wh).onHand()).isEqualTo(65);
    }

    @Test
    void a_failed_deduction_is_remembered_so_retries_get_a_stable_answer() {
        // 库存不足不抛异常，而是固化成一条 FAILED 记录。
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 3);
        var failed = e.deduct("ORDER-1", tenant, sku, wh, 10);
        assertThat(failed.status()).isEqualTo(DeductionStatus.FAILED);
        assertThat(failed.error()).contains("库存不足");
        // 补货之后重试：结论仍然是 FAILED，而不是「上次失败这次成功」这种不一致。
        e.stockIn(sku, wh, 100);
        assertThat(e.deduct("ORDER-1", tenant, sku, wh, 10).status())
                .isEqualTo(DeductionStatus.FAILED);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(103);
    }

    @Test
    void reserve_with_the_same_id_and_same_arguments_is_idempotent() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var first = e.reserve("R1", tenant, sku, wh, 4, null);
        var again = e.reserve("R1", tenant, sku, wh, 4, null);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.status()).isEqualTo(ReservationStatus.ACTIVE);
        // 幂等重放不能重复占用库存。
        assertThat(e.position(sku, wh).allocated()).isEqualTo(4);
    }

    @Test
    void reserve_with_the_same_id_but_a_different_quantity_conflicts() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, null);
        assertThatThrownBy(() -> e.reserve("R1", tenant, sku, wh, 5, null))
                .isInstanceOf(ReservationConflictException.class);
        assertThat(e.position(sku, wh).allocated()).isEqualTo(4);
    }

    @Test
    void reserve_with_the_same_id_but_a_different_sku_conflicts() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, null);
        assertThatThrownBy(() -> e.reserve("R1", tenant, Sku.of("OTHER"), wh, 4, null))
                .isInstanceOf(ReservationConflictException.class);
    }

    @Test
    void reserve_with_the_same_id_but_a_different_tenant_conflicts() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, null);
        assertThatThrownBy(() -> e.reserve("R1", StockyardTenant.of("other"), sku, wh, 4, null))
                .isInstanceOf(ReservationConflictException.class);
    }

    @Test
    void reserve_with_the_same_id_but_a_different_warehouse_conflicts() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, null);
        assertThatThrownBy(() -> e.reserve("R1", tenant, sku, Warehouse.of("OTHER"), 4, null))
                .isInstanceOf(ReservationConflictException.class);
    }

    @Test
    void confirming_twice_deducts_once() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        String id = e.reserve("R1", tenant, sku, wh, 4, null).id();
        e.confirm(id);
        e.confirm(id);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(6);
        assertThat(e.position(sku, wh).allocated()).isZero();
    }

    @Test
    void cancelling_twice_releases_once() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        String id = e.reserve("R1", tenant, sku, wh, 4, null).id();
        e.cancel(id, "x");
        e.cancel(id, "y");
        // 释放两次会把库存算成虚高（凭空造货），因此必须幂等。
        assertThat(e.position(sku, wh).onHand()).isEqualTo(10);
        assertThat(e.position(sku, wh).allocated()).isZero();
        assertThat(e.position(sku, wh).available()).isEqualTo(10);
    }
}
