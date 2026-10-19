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

import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.reservation.Reservation;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预占存储的「有界 + LRU + 淘汰可观测」契约。
 *
 * <p>为什么这一组断言必须存在：预占单的数量与来过的请求数成正比，而请求是无限的。放任它增长就是 内存泄漏；但一旦开始淘汰，被丢掉的单子表现为「从来没存在过」，排查起来只能靠猜。所以这里同时钉住
 * 三件事：容量真的有上限、淘汰顺序符合 LRU、淘汰次数可以被读出来。
 */
class InMemoryReservationStoreLruTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private Reservation reservation(String id) {
        return Reservation.pending(tenant, sku, wh, 1, null, CLOCK).toBuilder().id(id).build();
    }

    @Test
    void store_is_bounded_and_the_count_never_exceeds_capacity() {
        InMemoryReservationStore store = new InMemoryReservationStore(3);
        for (int i = 0; i < 50; i++) {
            store.save(reservation("R" + i));
        }
        assertThat(store.findAll()).hasSize(3);
    }

    @Test
    void exceeding_capacity_evicts_the_least_recently_used_entry() {
        InMemoryReservationStore store = new InMemoryReservationStore(2);
        store.save(reservation("A"));
        store.save(reservation("B"));
        store.save(reservation("C"));
        assertThat(store.find("A")).isEmpty();
        assertThat(store.find("B")).isPresent();
        assertThat(store.find("C")).isPresent();
        assertThat(store.evictedCount()).isEqualTo(1L);
    }

    @Test
    void reading_an_entry_counts_as_access_and_protects_it_from_eviction() {
        // 这是 LRU 与 FIFO 的分水岭：刚被读过的「老」条目必须活下来，最久没被碰过的才该走。
        InMemoryReservationStore store = new InMemoryReservationStore(2);
        store.save(reservation("A"));
        store.save(reservation("B"));
        assertThat(store.find("A")).isPresent(); // A 被访问，移到队尾
        store.save(reservation("C")); // 淘汰的应该是 B 而不是 A
        assertThat(store.find("A")).isPresent();
        assertThat(store.find("B")).isEmpty();
        assertThat(store.find("C")).isPresent();
    }

    @Test
    void overwriting_an_existing_id_counts_as_access_and_does_not_evict_anything() {
        InMemoryReservationStore store = new InMemoryReservationStore(2);
        store.save(reservation("A"));
        store.save(reservation("B"));
        store.save(reservation("A")); // upsert，不是新条目
        assertThat(store.evictedCount()).isZero();
        assertThat(store.findAll()).hasSize(2);
    }

    @Test
    void capacity_of_one_keeps_only_the_most_recent_entry() {
        InMemoryReservationStore store = new InMemoryReservationStore(1);
        store.save(reservation("A"));
        store.save(reservation("B"));
        assertThat(store.find("A")).isEmpty();
        assertThat(store.find("B")).isPresent();
        assertThat(store.evictedCount()).isEqualTo(1L);
    }

    @Test
    void non_positive_capacity_degrades_to_one_instead_of_throwing() {
        // 容量 0 / 负数显然是配置错误；退化成 1 至少保证「有界」这个核心不变量仍然成立。
        assertThat(new InMemoryReservationStore(0).evictedCount()).isZero();
        InMemoryReservationStore store = new InMemoryReservationStore(-5);
        store.save(reservation("A"));
        store.save(reservation("B"));
        assertThat(store.findAll()).hasSize(1);
    }

    @Test
    void eviction_is_observable_through_a_counter() {
        InMemoryReservationStore store = new InMemoryReservationStore(2);
        assertThat(store.evictedCount()).isZero();
        for (int i = 0; i < 10; i++) {
            store.save(reservation("R" + i));
        }
        // 10 次写入、容量 2：共淘汰 8 条。
        assertThat(store.evictedCount()).isEqualTo(8L);
    }

    @Test
    void save_returns_the_same_instance_so_callers_can_chain() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        Reservation r = reservation("A");
        assertThat(store.save(r)).isSameAs(r);
    }

    @Test
    void findAll_returns_a_snapshot_that_callers_cannot_use_to_mutate_the_store() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        store.save(reservation("A"));
        store.findAll().clear();
        // TTL 扫描会遍历 findAll 的结果；如果它返回的是活视图，遍历期间的写入会触发
        // ConcurrentModificationException，把一次定时回收变成整个服务的抖动。
        assertThat(store.find("A")).isPresent();
    }

    @Test
    void default_capacity_is_generous_enough_for_single_process_demo_use() {
        assertThat(InMemoryReservationStore.DEFAULT_CAPACITY).isEqualTo(1_000_000L);
        InMemoryReservationStore store = new InMemoryReservationStore();
        for (int i = 0; i < 1000; i++) {
            store.save(reservation("R" + i));
        }
        assertThat(store.evictedCount()).isZero();
        assertThat(store.findAll()).hasSize(1000);
    }
}
