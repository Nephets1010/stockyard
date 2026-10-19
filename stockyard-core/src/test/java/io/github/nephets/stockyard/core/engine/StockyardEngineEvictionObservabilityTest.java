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
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 引擎在「存储被打满」时的降级行为。
 *
 * <p>为什么不测「淘汰发生了」而要测「淘汰被看见了」：淘汰本身是设计内的降级，可怕的是它静默发生 ——表现为预占单凭空消失、库存凭空归零、幂等去重失效，而日志里什么都没有。这里钉住的是：
 * 容量打满时引擎<b>继续可用</b>，且代价可以通过 {@code evictedCount()} 被读到。
 */
class StockyardEngineEvictionObservabilityTest {

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    @Test
    void the_engine_keeps_working_after_the_reservation_store_is_saturated() {
        InMemoryReservationStore reservations = new InMemoryReservationStore(2);
        StockyardEngine e =
                new StockyardEngine(
                        reservations,
                        new InMemoryStockPositionStore(),
                        new InMemoryDeductionStore());
        e.stockIn(sku, wh, 1000);

        assertThatCode(
                        () -> {
                            for (int i = 0; i < 50; i++) {
                                e.reserve("R" + i, tenant, sku, wh, 1, null);
                            }
                        })
                .doesNotThrowAnyException();

        // 降级发生了，但必须留痕：48 条被淘汰，一条都不能是静默的。
        assertThat(reservations.evictedCount()).isEqualTo(48L);
        assertThat(e.position(sku, wh).allocated()).isEqualTo(50);
    }

    @Test
    void the_engine_keeps_working_after_the_position_store_is_saturated() {
        InMemoryStockPositionStore positions = new InMemoryStockPositionStore(3);
        StockyardEngine e =
                new StockyardEngine(
                        new InMemoryReservationStore(), positions, new InMemoryDeductionStore());

        assertThatCode(
                        () -> {
                            for (int i = 0; i < 20; i++) {
                                e.stockIn(Sku.of("SKU-" + i), wh, 10);
                            }
                        })
                .doesNotThrowAnyException();

        assertThat(positions.evictedCount()).isEqualTo(17L);
        // 被淘汰的水位会「凭空归零」：这正是必须被观测到的降级。
        assertThat(e.position(Sku.of("SKU-0"), wh).onHand()).isZero();
    }

    @Test
    void a_saturated_deduction_store_reveals_that_idempotency_has_been_lost() {
        InMemoryDeductionStore deductions = new InMemoryDeductionStore(2);
        StockyardEngine e =
                new StockyardEngine(
                        new InMemoryReservationStore(),
                        new InMemoryStockPositionStore(),
                        deductions);
        e.stockIn(sku, wh, 1000);

        for (int i = 0; i < 20; i++) {
            e.deduct("D" + i, tenant, sku, wh, 1);
        }
        // 幂等键丢了 18 条：此时重复提交 D0 会被当成首次请求再扣一次——这是必须报警的状态。
        assertThat(deductions.evictedCount()).isEqualTo(18L);
        assertThat(e.deduct("D0", tenant, sku, wh, 1).status().name()).isEqualTo("SUCCESS");
        assertThat(e.position(sku, wh).onHand()).isEqualTo(1000 - 20 - 1);
    }
}
