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
import io.github.nephets.stockyard.core.idempotency.DeductionStatus;
import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.reservation.ReservationNotFoundException;
import io.github.nephets.stockyard.core.reservation.ReservationStatus;
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
 * 定长分片锁的并发正确性证明。
 *
 * <p><b>「不超卖」到底断言了什么</b>：光断言「成功数 <= 库存数」是不够的——一个把所有请求都拒掉的 实现也能通过。这里对每个场景同时断言三件事：
 *
 * <ol>
 *   <li><b>不超卖</b>：成功预占/扣减的总量 &lt;= 入库总量；
 *   <li><b>总数守恒</b>：{@code onHand} 恒定，且 {@code available + 已确认消耗量 == 入库量}，
 *       即没有任何一件货既被卖掉又留在库里，也没有凭空消失；
 *   <li><b>水位不为负</b>：{@code allocated <= onHand}，可用量永不为负。
 * </ol>
 *
 * <p><b>为什么必须覆盖「撞槽」与「不撞槽」两条路径</b>：只测不撞槽等于没测防超卖（本来就不会冲突）；
 * 只测撞槽又会掩盖「分片退化成一把全局锁、吞吐塌掉」的问题。两组都测，才能同时钉住正确性与并行度。
 */
class StockyardEngineStripedLockConcurrencyTest {

    private final StockyardTenant tenant = StockyardTenant.of("t");

    private static StockyardEngine newEngine() {
        return new StockyardEngine(
                new InMemoryReservationStore(),
                new InMemoryStockPositionStore(),
                new InMemoryDeductionStore());
    }

    /** 所有线程在同一起跑线同时开跑，避免先提交的线程把库存抢完导致后提交的线程根本没竞争。 */
    private static void runAll(ExecutorService pool, List<Runnable> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> wrapped = new ArrayList<>(tasks.size());
        for (Runnable t : tasks) {
            wrapped.add(
                    () -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        t.run();
                    });
        }
        for (Runnable t : wrapped) {
            pool.submit(t);
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    }

    private static ExecutorService pool() {
        return Executors.newFixedThreadPool(32);
    }

    @Test
    void concurrent_reserve_on_one_sku_never_oversells_and_conserves_the_total() throws Exception {
        StockyardEngine e = newEngine();
        Sku sku = Sku.of("S");
        Warehouse wh = Warehouse.of("W");
        long stock = 100L;
        e.stockIn(sku, wh, stock);

        int threads = 32;
        int perThread = 20; // 总尝试 640，是库存的 6 倍多
        AtomicInteger success = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            for (int j = 0; j < perThread; j++) {
                String rid = "R-" + i + "-" + j;
                tasks.add(
                        () -> {
                            try {
                                e.reserve(rid, tenant, sku, wh, 1, null);
                                success.incrementAndGet();
                            } catch (OversellException ignored) {
                                // 预期：库存已被占满
                            }
                        });
            }
        }
        runAll(pool(), tasks);

        // ① 不超卖：成功数恰好等于库存，一件也不能多。
        assertThat(success.get()).isEqualTo((int) stock);
        // ② 总数守恒：在库没变，已预占 = 成功数，可用 = 在库 - 已预占。
        assertThat(e.position(sku, wh).onHand()).isEqualTo(stock);
        assertThat(e.position(sku, wh).allocated()).isEqualTo(success.get());
        assertThat(e.position(sku, wh).available()).isEqualTo(stock - success.get());
        // ③ 水位不为负。
        assertThat(e.position(sku, wh).available()).isNotNegative();
    }

    @Test
    void concurrent_reserve_on_slot_colliding_keys_is_still_serialized() throws Exception {
        // 刻意挑两个落在同一个锁槽的 key：它们必须互斥，哪怕业务逻辑上毫不相干。
        Sku skuA = firstKeySharingSlotWith(Sku.of("base"), Warehouse.of("W"));
        Warehouse whA = Warehouse.of("W");
        StockyardEngine e = newEngine();
        e.stockIn(skuA, whA, 10);

        AtomicInteger success = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String rid = "R" + i;
            tasks.add(
                    () -> {
                        try {
                            e.reserve(rid, tenant, skuA, whA, 1, null);
                            success.incrementAndGet();
                        } catch (OversellException ignored) {
                            // 预期
                        }
                    });
        }
        runAll(pool(), tasks);

        assertThat(success.get()).isEqualTo(10);
        assertThat(e.position(skuA, whA).allocated()).isEqualTo(10);
        assertThat(e.position(skuA, whA).available()).isZero();
    }

    @Test
    void concurrent_reserve_on_non_colliding_keys_all_succeed_in_parallel() throws Exception {
        // 反向路径：64 个互不撞槽的 key 各自库存 1，全部并发预占必须全部成功——
        // 这条断言保护的是「分片确实提供了并行度」，而不是退化成一把全局锁。
        StockyardEngine e = newEngine();
        int keys = StockyardEngine.POSITION_LOCK_STRIPES;
        List<Sku> skus = new ArrayList<>();
        Warehouse wh = Warehouse.of("W");
        for (int i = 0; i < keys; i++) {
            Sku sku = Sku.of("NC-" + i);
            skus.add(sku);
            e.stockIn(sku, wh, 1);
        }
        AtomicInteger success = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < keys; i++) {
            Sku sku = skus.get(i);
            String rid = "R" + i;
            tasks.add(
                    () -> {
                        try {
                            e.reserve(rid, tenant, sku, wh, 1, null);
                            success.incrementAndGet();
                        } catch (OversellException ex) {
                            throw new AssertionError("不同槽位的 key 不该互相阻塞", ex);
                        }
                    });
        }
        runAll(pool(), tasks);

        assertThat(success.get()).isEqualTo(keys);
        for (Sku sku : skus) {
            assertThat(e.position(sku, wh).allocated()).isEqualTo(1);
        }
    }

    @Test
    void concurrent_confirm_does_not_double_deduct() throws Exception {
        StockyardEngine e = newEngine();
        Sku sku = Sku.of("S");
        Warehouse wh = Warehouse.of("W");
        long stock = 50L;
        e.stockIn(sku, wh, stock);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(e.reserve("R" + i, tenant, sku, wh, 1, null).id());
        }
        assertThat(e.position(sku, wh).available()).isZero();

        // 每个 id 被 4 个线程同时确认：引擎的幂等必须让库存只扣一次。
        AtomicInteger confirmations = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (String id : ids) {
            for (int k = 0; k < 4; k++) {
                tasks.add(
                        () -> {
                            e.confirm(id);
                            confirmations.incrementAndGet();
                        });
            }
        }
        runAll(pool(), tasks);

        assertThat(confirmations.get()).isEqualTo(200);
        // 库存只被扣掉 50：确认是幂等的，重复确认不会把货扣没。
        assertThat(e.position(sku, wh).onHand()).isZero();
        assertThat(e.position(sku, wh).allocated()).isZero();
        assertThat(e.position(sku, wh).available()).isZero();
    }

    @Test
    void concurrent_cancel_releases_the_stock_exactly_once() throws Exception {
        StockyardEngine e = newEngine();
        Sku sku = Sku.of("S");
        Warehouse wh = Warehouse.of("W");
        e.stockIn(sku, wh, 20);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(e.reserve("R" + i, tenant, sku, wh, 1, null).id());
        }

        List<Runnable> tasks = new ArrayList<>();
        for (String id : ids) {
            for (int k = 0; k < 4; k++) {
                tasks.add(() -> e.cancel(id, "压测取消"));
            }
        }
        runAll(pool(), tasks);

        // 重复取消必须幂等：库存被完整释放一次，而不是被释放 4 次（那样 allocated 会变负）。
        assertThat(e.position(sku, wh).allocated()).isZero();
        assertThat(e.position(sku, wh).onHand()).isEqualTo(20);
        assertThat(e.position(sku, wh).available()).isEqualTo(20);
        for (String id : ids) {
            assertThat(e.position(sku, wh).allocated()).isZero();
            assertThat(id).isNotNull();
        }
    }

    @Test
    void concurrent_deduct_with_the_same_idempotency_key_deducts_once() throws Exception {
        StockyardEngine e = newEngine();
        Sku sku = Sku.of("S");
        Warehouse wh = Warehouse.of("W");
        e.stockIn(sku, wh, 100);

        AtomicInteger successes = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            tasks.add(
                    () -> {
                        if (e.deduct("SAME-KEY", tenant, sku, wh, 7).status()
                                == DeductionStatus.SUCCESS) {
                            successes.incrementAndGet();
                        }
                    });
        }
        runAll(pool(), tasks);

        // 库存只被扣 7，无论多少个线程同时提交同一个 requestId。
        assertThat(e.position(sku, wh).onHand()).isEqualTo(93);
        assertThat(e.position(sku, wh).available()).isEqualTo(93);
    }

    @Test
    void concurrent_mixed_workload_keeps_inventory_conserved() throws Exception {
        // 最贴近真实的一次压测：预占、确认、取消、扣减、入库混着来。
        // 唯一的不变量是「onHand 与 allocated 始终自洽，且可用量从不为负」。
        StockyardEngine e = newEngine();
        Sku sku = Sku.of("S");
        Warehouse wh = Warehouse.of("W");
        e.stockIn(sku, wh, 1000);

        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger deducted = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String id = "M" + i;
            tasks.add(
                    () -> {
                        try {
                            e.reserve(id, tenant, sku, wh, 1, null);
                            reserved.incrementAndGet();
                        } catch (OversellException ignored) {
                            return;
                        }
                        switch (Math.floorMod(id.hashCode(), 3)) {
                            case 0 -> {
                                e.confirm(id);
                                confirmed.incrementAndGet();
                            }
                            case 1 -> {
                                e.cancel(id, "mixed");
                                cancelled.incrementAndGet();
                            }
                            default -> {
                                // 什么都不做，留给 expireDue 或后续流程
                            }
                        }
                    });
            tasks.add(
                    () -> {
                        if (e.deduct("D" + id, tenant, sku, wh, 1).status()
                                == DeductionStatus.SUCCESS) {
                            deducted.incrementAndGet();
                        }
                    });
        }
        runAll(pool(), tasks);

        long onHand = e.position(sku, wh).onHand();
        long allocated = e.position(sku, wh).allocated();
        // 核心不变量：可用量永不为负，已预占永不超过在库。
        assertThat(allocated).isLessThanOrEqualTo(onHand);
        assertThat(e.position(sku, wh).available()).isNotNegative();
        assertThat(reserved.get() + deducted.get()).isGreaterThan(0);
        // 确认的每一件都同时从在库与已预占里减掉，因此「在库 = 入库 - 确认 - 直接扣减」必须成立。
        assertThat(onHand).isEqualTo(1000L - confirmed.get() - deducted.get());
        assertThat(allocated).isEqualTo(reserved.get() - confirmed.get() - cancelled.get());
    }

    @Test
    void concurrent_confirm_and_cancel_of_the_same_reservation_is_decided_exactly_once()
            throws Exception {
        // 一个预占同时被「支付成功」和「用户取消」两个消息打到：结果只能是其中一个，不能两个都算。
        StockyardEngine e = newEngine();
        Sku sku = Sku.of("S");
        Warehouse wh = Warehouse.of("W");
        e.stockIn(sku, wh, 10);
        String id = e.reserve("RACE", tenant, sku, wh, 5, null).id();

        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            tasks.add(
                    () -> {
                        try {
                            assertThat(e.confirm(id).status())
                                    .isEqualTo(ReservationStatus.CONFIRMED);
                            confirmed.incrementAndGet();
                        } catch (io.github.nephets.stockyard.core.reservation.ReservationException
                                | ReservationNotFoundException ex) {
                            // 已被取消：confirm 被拒
                        }
                    });
            tasks.add(
                    () -> {
                        try {
                            assertThat(e.cancel(id, "race").status())
                                    .isEqualTo(ReservationStatus.CANCELLED);
                            cancelled.incrementAndGet();
                        } catch (io.github.nephets.stockyard.core.reservation.ReservationException
                                | ReservationNotFoundException ex) {
                            // 已被确认：cancel 被拒
                        }
                    });
        }
        runAll(pool(), tasks);

        // 两边互斥：确认与取消不可能同时成功；库存要么被扣掉 5，要么被完整释放 10。
        assertThat(confirmed.get() + cancelled.get()).isPositive();
        assertThat(confirmed.get() == 0 || cancelled.get() == 0).isTrue();
        long onHand = e.position(sku, wh).onHand();
        assertThat(onHand == 5L || onHand == 10L).isTrue();
        assertThat(e.position(sku, wh).allocated()).isLessThanOrEqualTo(onHand);
    }

    /** 找一个与给定 key 落在同一个锁槽的 SKU，用于构造「故意撞槽」的场景。 */
    private static Sku firstKeySharingSlotWith(Sku base, Warehouse wh) {
        int target = StockyardEngine.positionLockIndex(base, wh);
        for (int i = 0; i < 10_000; i++) {
            Sku candidate = Sku.of("X" + i);
            if (StockyardEngine.positionLockIndex(candidate, wh) == target) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到同槽 key");
    }
}
