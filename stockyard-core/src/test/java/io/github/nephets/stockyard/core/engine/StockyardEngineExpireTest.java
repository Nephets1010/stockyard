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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StockyardEngineExpireTest {

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
    void expireDue_releases_expired() throws Exception {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var r = e.reserve("R1", tenant, sku, wh, 4, 1L);
        Thread.sleep(1100);
        var expired = e.expireDue(Instant.now());
        assertThat(expired).contains(r.id());
        assertThat(e.position(sku, wh).allocated()).isZero();
        assertThat(e.position(sku, wh).available()).isEqualTo(10);
    }

    @Test
    void expireDue_ignores_active_within_ttl() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var r = e.reserve("R1", tenant, sku, wh, 4, 100L);
        var expired = e.expireDue(Instant.now());
        assertThat(expired).doesNotContain(r.id());
        assertThat(e.position(sku, wh).allocated()).isEqualTo(4);
    }

    @Test
    void expireDue_ignores_confirmed() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var r = e.reserve("R1", tenant, sku, wh, 4, null);
        e.confirm(r.id(), Instant.now());
        var expired = e.expireDue(Instant.now());
        assertThat(expired).doesNotContain(r.id());
        assertThat(e.position(sku, wh).allocated()).isZero();
    }

    @Test
    void reserve_after_expiry_with_new_id_is_active() throws Exception {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, 1L);
        Thread.sleep(1100);
        e.expireDue(Instant.now());
        assertThat(e.position(sku, wh).available()).isEqualTo(10);
        var r2 = e.reserve("R2", tenant, sku, wh, 4, null);
        assertThat(r2.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(e.position(sku, wh).available()).isEqualTo(6);
    }
}
