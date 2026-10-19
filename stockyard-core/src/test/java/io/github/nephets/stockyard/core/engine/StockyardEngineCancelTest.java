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
import io.github.nephets.stockyard.core.reservation.ReservationException;
import io.github.nephets.stockyard.core.reservation.ReservationNotFoundException;
import io.github.nephets.stockyard.core.reservation.ReservationStatus;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockyardEngineCancelTest {

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
    void cancel_releases_allocation() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var r = e.reserve("R1", tenant, sku, wh, 4, null);
        var c = e.cancel(r.id(), Instant.now(), "no need");
        assertThat(c.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(e.position(sku, wh).allocated()).isZero();
        assertThat(e.position(sku, wh).available()).isEqualTo(10);
    }

    @Test
    void cancel_idempotent() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var r = e.reserve("R1", tenant, sku, wh, 4, null);
        var c1 = e.cancel(r.id(), Instant.now(), null);
        var c2 = e.cancel(r.id(), Instant.now(), null);
        assertThat(c1).isSameAs(c2);
        assertThat(e.position(sku, wh).allocated()).isZero();
    }

    @Test
    void cancel_confirmed_throws() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var r = e.reserve("R1", tenant, sku, wh, 4, null);
        e.confirm(r.id(), Instant.now());
        assertThatThrownBy(() -> e.cancel(r.id(), Instant.now(), null))
                .isInstanceOf(ReservationException.class);
    }

    @Test
    void cancel_missing_throws() {
        StockyardEngine e = newEngine();
        assertThatThrownBy(() -> e.cancel("nope", Instant.now(), null))
                .isInstanceOf(ReservationNotFoundException.class);
    }
}
