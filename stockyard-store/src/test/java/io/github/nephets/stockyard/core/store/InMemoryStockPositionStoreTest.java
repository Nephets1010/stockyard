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
package io.github.nephets.stockyard.core.store;

import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.StockPosition;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryStockPositionStoreTest {

    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    @Test
    void getOrCreate_is_zero() {
        InMemoryStockPositionStore s = new InMemoryStockPositionStore();
        assertThat(s.getOrCreate(sku, wh).available()).isZero();
    }

    @Test
    void save_then_find() {
        InMemoryStockPositionStore s = new InMemoryStockPositionStore();
        StockPosition p = StockPosition.of(sku, wh).receive(10);
        s.save(p);
        assertThat(s.find(sku, wh)).contains(p);
    }

    @Test
    void getOrCreate_idempotent() {
        InMemoryStockPositionStore s = new InMemoryStockPositionStore();
        assertThat(s.getOrCreate(sku, wh)).isSameAs(s.getOrCreate(sku, wh));
    }

    @Test
    void evicts_oldest_when_over_capacity() {
        InMemoryStockPositionStore s = new InMemoryStockPositionStore(3);
        for (int i = 1; i <= 5; i++) {
            s.save(StockPosition.of(Sku.of("S" + i), Warehouse.of("W" + i)).receive(i));
        }
        assertThat(s.find(Sku.of("S1"), Warehouse.of("W1"))).isEmpty(); // 最旧的水位已被淘汰
        assertThat(s.find(Sku.of("S2"), Warehouse.of("W2"))).isEmpty();
        assertThat(s.find(Sku.of("S5"), Warehouse.of("W5"))).isPresent(); // 最新的水位仍在
    }

    @Test
    void eviction_is_observable_instead_of_silent() {
        InMemoryStockPositionStore s = new InMemoryStockPositionStore(2);
        assertThat(s.evictedCount()).isZero();
        for (int i = 1; i <= 5; i++) {
            s.save(StockPosition.of(Sku.of("S" + i), Warehouse.of("W" + i)).receive(i));
        }
        // 水位被淘汰会让库存「凭空归零」，必须能从计数器上看到，而不是静默发生。
        assertThat(s.evictedCount()).isEqualTo(3L);
    }
}
