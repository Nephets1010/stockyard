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
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StockyardEngineDeductTest {

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
    void deduct_reduces_onhand() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var rec = e.deduct("D1", tenant, sku, wh, 3, Instant.now());
        assertThat(rec.status()).isEqualTo(DeductionStatus.SUCCESS);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(7);
    }

    @Test
    void deduct_idempotent() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 10);
        var a = e.deduct("D1", tenant, sku, wh, 3, Instant.now());
        var b = e.deduct("D1", tenant, sku, wh, 3, Instant.now());
        assertThat(a).isEqualTo(b);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(7);
    }

    @Test
    void deduct_oversell_returns_failed_without_changing_stock() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 2);
        var rec = e.deduct("D1", tenant, sku, wh, 5, Instant.now());
        assertThat(rec.status()).isEqualTo(DeductionStatus.FAILED);
        assertThat(rec.error()).contains("库存不足");
        assertThat(e.position(sku, wh).onHand()).isEqualTo(2);
    }

    @Test
    void deduct_idempotent_even_when_failed() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 2);
        var a = e.deduct("D1", tenant, sku, wh, 5, Instant.now());
        var b = e.deduct("D1", tenant, sku, wh, 5, Instant.now());
        assertThat(a).isEqualTo(b);
        assertThat(a.status()).isEqualTo(DeductionStatus.FAILED);
    }

    @Test
    void failed_record_is_persisted() {
        StockyardEngine e = newEngine();
        DeductionStore store = new InMemoryDeductionStore();
        StockyardEngine e2 =
                new StockyardEngine(
                        new InMemoryReservationStore(), new InMemoryStockPositionStore(), store);
        e2.stockIn(sku, wh, 2);
        e2.deduct("D1", tenant, sku, wh, 5, Instant.now());
        assertThat(store.find("D1")).isPresent();
    }
}
