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

import io.github.nephets.stockyard.core.OversellException;
import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * per-(sku,warehouse) 锁的容量与正确性回归测试。
 *
 * <p>背景：引擎曾用 {@code ConcurrentHashMap} 按需建锁，条目数等于出现过的 (sku,warehouse) 组合数，
 * 是长生命周期的无界集合（SKU×仓库规模一大就是内存泄漏）。改为定长分片锁后必须证明两件事：锁表不再 随 key 增长，且分片复用不会破坏「同一 (sku,warehouse)
 * 串行」这一防超卖前提。
 */
class StockyardEngineLockStripesTest {

    private final StockyardTenant tenant = StockyardTenant.of("t");

    private static StockyardEngine newEngine() {
        return new StockyardEngine(
                new InMemoryReservationStore(),
                new InMemoryStockPositionStore(),
                new InMemoryDeductionStore());
    }

    @Test
    void lock_table_is_bounded_and_does_not_grow_with_distinct_keys() {
        StockyardEngine e = newEngine();
        int distinctKeys = StockyardEngine.POSITION_LOCK_STRIPES * 20; // 远超分片数
        for (int i = 0; i < distinctKeys; i++) {
            e.stockIn(Sku.of("S" + i), Warehouse.of("W" + i), 1);
        }
        // 关键断言：摸过这么多 key 之后锁表大小仍等于分片数，没有随 key 增长。
        assertThat(e.positionLockTableSize()).isEqualTo(StockyardEngine.POSITION_LOCK_STRIPES);
        assertThat(StockyardEngine.POSITION_LOCK_STRIPES).isPositive();
    }

    @Test
    void striped_locks_still_serialize_same_key_under_contention() throws Exception {
        StockyardEngine e = newEngine();
        int keys = StockyardEngine.POSITION_LOCK_STRIPES * 4; // 必然发生分片复用
        for (int i = 0; i < keys; i++) {
            e.stockIn(Sku.of("S" + i), Warehouse.of("W" + i), 1);
        }

        // 每个 key 库存只有 1，却并发抢 3 次：成功数必须正好等于 key 数，多一个就是超卖。
        int attemptsPerKey = 3;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < keys; i++) {
            Sku sku = Sku.of("S" + i);
            Warehouse wh = Warehouse.of("W" + i);
            for (int a = 0; a < attemptsPerKey; a++) {
                String rid = "R-" + i + "-" + a;
                tasks.add(
                        () -> {
                            try {
                                start.await();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            try {
                                e.reserve(rid, tenant, sku, wh, 1, null);
                                success.incrementAndGet();
                            } catch (OversellException ignored) {
                                // 预期：该 key 的 1 件库存已被别人占走
                            }
                        });
            }
        }
        for (Runnable t : tasks) {
            pool.submit(t);
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(success.get()).isEqualTo(keys);
        for (int i = 0; i < keys; i++) {
            assertThat(e.position(Sku.of("S" + i), Warehouse.of("W" + i)).allocated())
                    .as("key %d 的已预占量必须恰好等于入库量", i)
                    .isEqualTo(1L);
        }
    }
}
