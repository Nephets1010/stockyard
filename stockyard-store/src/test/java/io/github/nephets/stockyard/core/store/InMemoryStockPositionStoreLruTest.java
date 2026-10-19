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

/**
 * 库存水位存储的「有界 + LRU + key 组成」契约。
 *
 * <p>为什么水位被淘汰是最需要留痕的一种：水位丢了表现为这个 (sku,warehouse) 的库存「凭空归零」——
 * 不是卖完了，而是记录没了。若再叠加自动补货，会出现「明明有货却一直补、一补就又归零」的循环。
 */
class InMemoryStockPositionStoreLruTest {

    private final Sku skuA = Sku.of("A");
    private final Sku skuB = Sku.of("B");
    private final Warehouse wh1 = Warehouse.of("WH1");
    private final Warehouse wh2 = Warehouse.of("WH2");

    @Test
    void unknown_position_is_absent_until_getOrCreate_creates_it() {
        InMemoryStockPositionStore store = new InMemoryStockPositionStore();
        assertThat(store.find(skuA, wh1)).isEmpty();
        assertThat(store.getOrCreate(skuA, wh1).onHand()).isZero();
        assertThat(store.find(skuA, wh1)).isPresent();
    }

    @Test
    void key_combines_sku_and_warehouse() {
        // 同一个 SKU 在两个仓必须是两条独立水位；混在一起就是串仓。
        InMemoryStockPositionStore store = new InMemoryStockPositionStore();
        store.save(store.getOrCreate(skuA, wh1).receive(10));
        store.save(store.getOrCreate(skuA, wh2).receive(20));
        store.save(store.getOrCreate(skuB, wh1).receive(30));
        assertThat(store.find(skuA, wh1).orElseThrow().onHand()).isEqualTo(10);
        assertThat(store.find(skuA, wh2).orElseThrow().onHand()).isEqualTo(20);
        assertThat(store.find(skuB, wh1).orElseThrow().onHand()).isEqualTo(30);
        assertThat(store.find(skuB, wh2)).isEmpty();
    }

    @Test
    void save_overwrites_the_previous_position() {
        InMemoryStockPositionStore store = new InMemoryStockPositionStore();
        store.save(store.getOrCreate(skuA, wh1).receive(10));
        store.save(store.find(skuA, wh1).orElseThrow().allocate(4));
        assertThat(store.find(skuA, wh1).orElseThrow().allocated()).isEqualTo(4);
        assertThat(store.find(skuA, wh1).orElseThrow().onHand()).isEqualTo(10);
    }

    @Test
    void exceeding_capacity_evicts_the_least_recently_used_position() {
        InMemoryStockPositionStore store = new InMemoryStockPositionStore(2);
        store.save(store.getOrCreate(skuA, wh1).receive(1));
        store.save(store.getOrCreate(skuA, wh2).receive(2));
        store.save(store.getOrCreate(skuB, wh1).receive(3));
        assertThat(store.find(skuA, wh1)).isEmpty();
        assertThat(store.find(skuA, wh2)).isPresent();
        assertThat(store.find(skuB, wh1)).isPresent();
        assertThat(store.evictedCount()).isEqualTo(1L);
    }

    @Test
    void reading_a_position_counts_as_access_and_protects_it_from_eviction() {
        InMemoryStockPositionStore store = new InMemoryStockPositionStore(2);
        store.save(store.getOrCreate(skuA, wh1).receive(1));
        store.save(store.getOrCreate(skuA, wh2).receive(2));
        assertThat(store.find(skuA, wh1)).isPresent(); // A/WH1 变成最近使用
        store.save(store.getOrCreate(skuB, wh1).receive(3)); // 淘汰 A/WH2
        assertThat(store.find(skuA, wh1)).isPresent();
        assertThat(store.find(skuA, wh2)).isEmpty();
    }

    @Test
    void getOrCreate_on_an_existing_key_counts_as_access_and_does_not_evict() {
        InMemoryStockPositionStore store = new InMemoryStockPositionStore(2);
        store.getOrCreate(skuA, wh1);
        store.getOrCreate(skuA, wh2);
        store.getOrCreate(skuA, wh1); // 命中已有条目，不是新增
        assertThat(store.evictedCount()).isZero();
        store.getOrCreate(skuB, wh1);
        assertThat(store.evictedCount()).isEqualTo(1L);
        assertThat(store.find(skuA, wh1)).isPresent();
    }

    @Test
    void an_evicted_position_comes_back_as_zero_inventory() {
        InMemoryStockPositionStore store = new InMemoryStockPositionStore(1);
        store.save(store.getOrCreate(skuA, wh1).receive(100));
        assertThat(store.find(skuA, wh1).orElseThrow().onHand()).isEqualTo(100);
        store.getOrCreate(skuB, wh1); // 淘汰 A/WH1
        // 这就是「库存凭空归零」：不是卖完了，而是记录没了。evictedCount() 是唯一的证据来源。
        assertThat(store.find(skuA, wh1)).isEmpty();
        assertThat(store.evictedCount()).isEqualTo(1L);
        // 再次取用只能拿到一条全新的零水位——100 件库存就这样凭空消失了。
        assertThat(store.getOrCreate(skuA, wh1).onHand()).isZero();
    }

    @Test
    void capacity_of_one_keeps_only_the_most_recently_used_position() {
        InMemoryStockPositionStore store = new InMemoryStockPositionStore(1);
        store.getOrCreate(skuA, wh1);
        store.getOrCreate(skuB, wh1);
        assertThat(store.find(skuA, wh1)).isEmpty();
        assertThat(store.find(skuB, wh1)).isPresent();
    }

    @Test
    void non_positive_capacity_degrades_to_one() {
        InMemoryStockPositionStore store = new InMemoryStockPositionStore(0);
        store.getOrCreate(skuA, wh1);
        store.getOrCreate(skuB, wh1);
        assertThat(store.find(skuA, wh1)).isEmpty();
        assertThat(store.evictedCount()).isEqualTo(1L);
    }

    @Test
    void eviction_counter_accumulates_exactly() {
        InMemoryStockPositionStore store = new InMemoryStockPositionStore(3);
        for (int i = 0; i < 20; i++) {
            store.getOrCreate(Sku.of("SKU-" + i), wh1);
        }
        assertThat(store.evictedCount()).isEqualTo(17L);
    }

    @Test
    void default_capacity_keeps_demo_workloads_intact() {
        assertThat(InMemoryStockPositionStore.DEFAULT_CAPACITY).isEqualTo(1_000_000L);
        InMemoryStockPositionStore store = new InMemoryStockPositionStore();
        for (int i = 0; i < 500; i++) {
            store.save(StockPosition.of(Sku.of("SKU-" + i), wh1).receive(i));
        }
        assertThat(store.evictedCount()).isZero();
    }
}
