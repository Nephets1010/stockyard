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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等记录存储的「有界 + LRU + 淘汰可观测」契约。
 *
 * <p>为什么这里的淘汰是<strong>最危险</strong>的一种静默降级：预占单丢了表现为「查不到」，水位丢了 表现为「归零」，都还能从现象反推；而幂等记录被淘汰后，同一个
 * requestId 的下一次重试会被重新判定为 「首次提交」，<b>于是同一笔业务被扣两次</b>——现象是资损，原因却藏在容量配置里。所以 {@link
 * InMemoryDeductionStore#evictedCount()} 必须被纳入监控：只要它不为 0，幂等保证就已失效。
 */
class InMemoryDeductionStoreLruTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private DeductionRecord record(String requestId) {
        return DeductionRecord.success(requestId, tenant, sku, wh, 1, CLOCK);
    }

    @Test
    void unknown_request_id_is_absent() {
        assertThat(new InMemoryDeductionStore().find("nope")).isEmpty();
    }

    @Test
    void saved_record_is_found_by_request_id() {
        InMemoryDeductionStore store = new InMemoryDeductionStore();
        store.save(record("D1"));
        assertThat(store.find("D1")).isPresent();
    }

    @Test
    void saving_the_same_request_id_again_overwrites_without_evicting() {
        InMemoryDeductionStore store = new InMemoryDeductionStore(2);
        store.save(record("D1"));
        store.save(record("D2"));
        store.save(record("D1"));
        assertThat(store.evictedCount()).isZero();
        assertThat(store.find("D1")).isPresent();
    }

    @Test
    void exceeding_capacity_evicts_the_least_recently_used_record() {
        InMemoryDeductionStore store = new InMemoryDeductionStore(2);
        store.save(record("D1"));
        store.save(record("D2"));
        store.save(record("D3"));
        assertThat(store.find("D1")).isEmpty();
        assertThat(store.find("D2")).isPresent();
        assertThat(store.find("D3")).isPresent();
        assertThat(store.evictedCount()).isEqualTo(1L);
    }

    @Test
    void a_retry_hit_counts_as_access_and_keeps_the_idempotency_key_alive() {
        // 长尾重试的守护断言：几小时前写入、但刚刚被重试命中的幂等键必须活下来，
        // 否则下一次重试就会重复扣减。这正是选 LRU 而不是 FIFO 的理由。
        InMemoryDeductionStore store = new InMemoryDeductionStore(2);
        store.save(record("OLD"));
        store.save(record("D2"));
        assertThat(store.find("OLD")).isPresent(); // 模拟一次延迟重投
        store.save(record("D3")); // 淘汰的应该是 D2
        assertThat(store.find("OLD")).isPresent();
        assertThat(store.find("D2")).isEmpty();
    }

    @Test
    void capacity_of_one_keeps_only_the_most_recent_key() {
        InMemoryDeductionStore store = new InMemoryDeductionStore(1);
        store.save(record("D1"));
        store.save(record("D2"));
        assertThat(store.find("D1")).isEmpty();
        assertThat(store.find("D2")).isPresent();
    }

    @Test
    void non_positive_capacity_degrades_to_one() {
        InMemoryDeductionStore store = new InMemoryDeductionStore(-1);
        store.save(record("D1"));
        store.save(record("D2"));
        assertThat(store.find("D1")).isEmpty();
        assertThat(store.evictedCount()).isEqualTo(1L);
    }

    @Test
    void eviction_counter_matches_the_number_of_lost_idempotency_keys() {
        InMemoryDeductionStore store = new InMemoryDeductionStore(5);
        for (int i = 0; i < 25; i++) {
            store.save(record("D" + i));
        }
        assertThat(store.evictedCount()).isEqualTo(20L);
    }

    @Test
    void a_fresh_store_has_never_lost_a_key() {
        assertThat(new InMemoryDeductionStore().evictedCount()).isZero();
        assertThat(new InMemoryDeductionStore(3).evictedCount()).isZero();
    }

    @Test
    void default_capacity_is_documented_and_generous() {
        assertThat(InMemoryDeductionStore.DEFAULT_CAPACITY).isEqualTo(1_000_000L);
        InMemoryDeductionStore store = new InMemoryDeductionStore();
        for (int i = 0; i < 2000; i++) {
            store.save(record("D" + i));
        }
        assertThat(store.evictedCount()).isZero();
        assertThat(store.find("D1999")).isPresent();
    }
}
