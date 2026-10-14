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

import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockyardEngineStockInTest {

    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private StockyardEngine newEngine() {
        return new StockyardEngine(
                new InMemoryReservationStore(),
                new InMemoryStockPositionStore(),
                new InMemoryDeductionStore());
    }

    @Test
    void stockIn_adds_onhand() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 5);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(5);
        assertThat(e.position(sku, wh).available()).isEqualTo(5);
    }

    @Test
    void stockIn_is_cumulative() {
        StockyardEngine e = newEngine();
        e.stockIn(sku, wh, 5);
        e.stockIn(sku, wh, 3);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(8);
    }

    @Test
    void position_getOrCreate_is_zero() {
        StockyardEngine e = newEngine();
        assertThat(e.position(sku, wh).available()).isZero();
    }
}
