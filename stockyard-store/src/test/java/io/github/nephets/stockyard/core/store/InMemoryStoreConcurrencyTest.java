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
import io.github.nephets.stockyard.core.idempotency.DeductionRecord;
import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.reservation.Reservation;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个内存存储在并发读写下的结构性安全。
 *
 * <p>为什么单独测这个：{@code LinkedHashMap} 的 {@code accessOrder=true} 会在 {@code get} 时改链表，
 * 而链表改动不是线程安全的。三个存储都用 {@code Collections.synchronizedMap} 包了一层，把「读 + 改链表」
 * 变成一个临界区——这个包装一旦被谁去掉，症状不是报错而是随机的丢数据/结构损坏，因此必须用并发测试 钉住。
 */
class InMemoryStoreConcurrencyTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private static void runConcurrently(int threads, int perThread, Runnable body)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(
                    () -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int j = 0; j < perThread; j++) {
                            body.run();
                        }
                    });
        }
        for (Runnable t : tasks) {
            pool.submit(t);
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void concurrent_reservation_store_reads_and_writes_stay_consistent() throws Exception {
        InMemoryReservationStore store = new InMemoryReservationStore(10_000);
        AtomicInteger seq = new AtomicInteger();
        runConcurrently(
                16,
                50,
                () -> {
                    int i = seq.incrementAndGet();
                    String id = "R" + i;
                    store.save(
                            Reservation.pending(tenant, sku, wh, 1, null, CLOCK).toBuilder()
                                    .id(id)
                                    .build());
                    store.find(id);
                    store.findAll();
                });
        // 容量足够，一次都不该淘汰；且所有写入的条目都能被读到。
        assertThat(store.evictedCount()).isZero();
        assertThat(store.findAll()).hasSize(800);
    }

    @Test
    void concurrent_position_store_updates_do_not_lose_the_last_writer() throws Exception {
        InMemoryStockPositionStore store = new InMemoryStockPositionStore();
        runConcurrently(
                16,
                50,
                () -> {
                    store.save(store.getOrCreate(sku, wh).receive(1));
                    store.find(sku, wh);
                });
        // 注意：这里刻意不断言 onHand == 800。内存存储不提供原子自增，「读—改—写」在并发下会丢更新，
        // 这正是引擎要用分片锁把这段串行化的原因。本测试只钉住「结构不损坏、最后一次写入生效」。
        assertThat(store.find(sku, wh)).isPresent();
        assertThat(store.find(sku, wh).orElseThrow().onHand()).isPositive();
        assertThat(store.evictedCount()).isZero();
    }

    @Test
    void concurrent_deduction_store_writes_keep_every_distinct_key() throws Exception {
        InMemoryDeductionStore store = new InMemoryDeductionStore(10_000);
        AtomicInteger seq = new AtomicInteger();
        runConcurrently(
                16,
                50,
                () -> {
                    String id = "D" + seq.incrementAndGet();
                    store.save(DeductionRecord.success(id, tenant, sku, wh, 1, CLOCK));
                    store.find(id);
                });
        assertThat(store.evictedCount()).isZero();
        for (int i = 1; i <= 800; i++) {
            assertThat(store.find("D" + i)).isPresent();
        }
    }

    @Test
    void concurrent_eviction_under_a_tiny_capacity_never_exceeds_the_bound() throws Exception {
        InMemoryReservationStore store = new InMemoryReservationStore(8);
        AtomicInteger seq = new AtomicInteger();
        runConcurrently(
                16,
                50,
                () ->
                        store.save(
                                Reservation.pending(tenant, sku, wh, 1, null, CLOCK).toBuilder()
                                        .id("R" + seq.incrementAndGet())
                                        .build()));
        // 容量 8：无论并发怎么打，活着的条目数都不许超过上限——有界性是硬不变量。
        assertThat(store.findAll()).hasSize(8);
        assertThat(store.evictedCount()).isEqualTo(792L);
    }
}
