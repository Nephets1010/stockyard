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
package io.github.nephets.stockyard.core.idempotency;

import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDeductionStoreTest {

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    @Test
    void find_missing_is_empty() {
        InMemoryDeductionStore s = new InMemoryDeductionStore();
        assertThat(s.find("x")).isEmpty();
    }

    @Test
    void save_then_find() {
        InMemoryDeductionStore s = new InMemoryDeductionStore();
        DeductionRecord r = DeductionRecord.success("r1", tenant, sku, wh, 5);
        s.save(r);
        assertThat(s.find("r1")).contains(r);
    }

    @Test
    void evicts_oldest_when_over_capacity() {
        InMemoryDeductionStore s = new InMemoryDeductionStore(3);
        for (int i = 1; i <= 5; i++) {
            s.save(DeductionRecord.success("r" + i, tenant, sku, wh, i));
        }
        assertThat(s.find("r1")).isEmpty(); // 最旧的记录已被淘汰
        assertThat(s.find("r2")).isEmpty();
        assertThat(s.find("r5")).isPresent(); // 最新的记录仍在
    }

    @Test
    void eviction_is_observable_instead_of_silent() {
        InMemoryDeductionStore s = new InMemoryDeductionStore(2);
        assertThat(s.evictedCount()).isZero();
        for (int i = 1; i <= 5; i++) {
            s.save(DeductionRecord.success("r" + i, tenant, sku, wh, i));
        }
        // 幂等记录被淘汰会让同一 requestId 重复扣减，必须能从计数器上看到，而不是静默发生。
        assertThat(s.evictedCount()).isEqualTo(3L);
    }
}
