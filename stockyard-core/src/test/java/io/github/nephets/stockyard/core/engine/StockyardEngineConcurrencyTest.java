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

/** 并发预占回归测试：库存水位用 per-(sku,warehouse) 锁串行化后，高并发预占绝不超卖。 修复前（check-then-act 非原子）成功预占数会超过实际库存。 */
class StockyardEngineConcurrencyTest {

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    @Test
    void concurrentReserveNeverOversells() throws Exception {
        StockyardEngine e =
                new StockyardEngine(
                        new InMemoryReservationStore(),
                        new InMemoryStockPositionStore(),
                        new InMemoryDeductionStore());
        e.stockIn(sku, wh, 100);

        int threads = 32;
        int perThread = 10; // 总尝试 320 远超库存 100
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int base = i * perThread;
            for (int j = 0; j < perThread; j++) {
                String rid = "R" + (base + j);
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
                                // 预期：库存不足被拒
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

        // 成功预占数必须正好等于库存，绝不超卖；库存不可为负。
        assertThat(success.get()).isEqualTo(100);
        assertThat(e.position(sku, wh).allocated()).isEqualTo(100);
        assertThat(e.position(sku, wh).onHand()).isEqualTo(100);
        assertThat(e.position(sku, wh).available()).isEqualTo(0);
    }
}
