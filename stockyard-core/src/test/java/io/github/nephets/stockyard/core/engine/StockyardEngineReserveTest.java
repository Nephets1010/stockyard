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
import io.github.nephets.stockyard.core.reservation.ReservationConflictException;
import io.github.nephets.stockyard.core.reservation.ReservationStatus;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockyardEngineReserveTest {

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private StockyardEngine newEngine() {
        return new StockyardEngine(
                new InMemoryReservationStore(),
                new InMemoryStockPositionStore(),
                new InMemoryDeductionStore());
    }

    @Test
    void reserve_allocates_stock() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var r = e.reserve("R1", tenant, sku, wh, 4, null);
        assertThat(r.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(e.position(sku, wh).allocated()).isEqualTo(4);
        assertThat(e.position(sku, wh).available()).isEqualTo(6);
    }

    @Test
    void reserve_idempotent_by_id() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var a = e.reserve("R1", tenant, sku, wh, 4, null);
        var b = e.reserve("R1", tenant, sku, wh, 4, null);
        assertThat(a).isSameAs(b);
        assertThat(e.position(sku, wh).allocated()).isEqualTo(4);
    }

    @Test
    void reserve_oversell_throws() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 3);
        assertThatThrownBy(() -> e.reserve("R1", tenant, sku, wh, 4, null))
                .isInstanceOf(OversellException.class);
        assertThat(e.position(sku, wh).allocated()).isZero();
    }

    @Test
    void reserve_conflict_on_different_params() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, null);
        assertThatThrownBy(() -> e.reserve("R1", tenant, sku, wh, 5, null))
                .isInstanceOf(ReservationConflictException.class);
    }
}
