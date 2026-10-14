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
import io.github.nephets.stockyard.core.idempotency.DeductionStore;
import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.reservation.ReservationStatus;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StockyardEngineFlowIT {

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    @Test
    void reserve_confirm_deduct_expire_flow() throws Exception {
        DeductionStore store = new InMemoryDeductionStore();
        StockyardEngine e =
                new StockyardEngine(
                        new InMemoryReservationStore(), new InMemoryStockPositionStore(), store);
        e.stockIn(sku, wh, 100);

        var r = e.reserve("R1", tenant, sku, wh, 10, null);
        assertThat(r.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(e.position(sku, wh).available()).isEqualTo(90);

        var c = e.confirm(r.id(), Instant.now());
        assertThat(c.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(90);

        var d1 = e.deduct("D1", tenant, sku, wh, 20, Instant.now());
        var d2 = e.deduct("D1", tenant, sku, wh, 20, Instant.now());
        assertThat(d1).isEqualTo(d2);
        assertThat(d1.status()).isEqualTo(DeductionStatus.SUCCESS);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(70);

        e.reserve("R2", tenant, sku, wh, 5, 1L);
        Thread.sleep(1100);
        var expired = e.expireDue(Instant.now());
        assertThat(expired).contains("R2");
        assertThat(e.position(sku, wh).available()).isEqualTo(70);
    }
}
