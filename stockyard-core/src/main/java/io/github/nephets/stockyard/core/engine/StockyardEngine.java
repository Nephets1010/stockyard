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
import io.github.nephets.stockyard.core.idempotency.DeductionRecord;
import io.github.nephets.stockyard.core.idempotency.DeductionStore;
import io.github.nephets.stockyard.core.reservation.Reservation;
import io.github.nephets.stockyard.core.reservation.ReservationConflictException;
import io.github.nephets.stockyard.core.reservation.ReservationNotFoundException;
import io.github.nephets.stockyard.core.reservation.ReservationStatus;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.StockPosition;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.ReservationStore;
import io.github.nephets.stockyard.core.store.StockPositionStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** 库存引擎：把预占状态机、库存水位与幂等扣减串起来。 */
public final class StockyardEngine {

    private static final Logger log = LoggerFactory.getLogger(StockyardEngine.class);

    /**
     * per-(sku,warehouse) 锁的分片数。
     *
     * <p>为什么是定长分片而不是「按需建锁 + 用后淘汰」：按需建的锁表条目数等于出现过的 (sku,warehouse)
     * 组合数，是长生命周期的无界集合，SKU×仓库规模一大就是内存泄漏；而对锁做 LRU 淘汰又更危险——同一 key
     * 的锁被淘汰后，新请求会拿到另一把锁，「读水位→改副本→save」就不再串行， 直接退化成超卖。定长分片锁构造后容量恒定（天然有上限），且同一 key 恒定映射到同一把锁，互斥
     * 语义不变，代价只是不同 key 可能共用一把锁、并行度略降——这是安全方向上的取舍。
     */
    static final int POSITION_LOCK_STRIPES = 64;

    private final ReservationStore reservationStore;
    private final StockPositionStore positionStore;
    private final DeductionStore deductionStore;

    // 同一 (sku,warehouse) 的库存水位在并发预占/确认/取消/扣减/入库时必须串行，否则「读水位→
    // 生成新副本→save」的 check-then-act 会超卖。用 per-key 锁把临界区串行化；不同 (sku,warehouse)
    // 之间仍可并发。生产环境更推荐把原子更新下沉到存储层（如 CAS），此处保持对 StockPositionStore
    // 实现的零侵入。
    private final Object[] positionLocks = new Object[POSITION_LOCK_STRIPES];

    public StockyardEngine(
            ReservationStore reservationStore,
            StockPositionStore positionStore,
            DeductionStore deductionStore) {
        this.reservationStore = Objects.requireNonNull(reservationStore, "reservationStore");
        this.positionStore = Objects.requireNonNull(positionStore, "positionStore");
        this.deductionStore = Objects.requireNonNull(deductionStore, "deductionStore");
        for (int i = 0; i < POSITION_LOCK_STRIPES; i++) {
            positionLocks[i] = new Object();
        }
    }

    private static int positionLockIndex(Sku sku, Warehouse warehouse) {
        return Math.floorMod(Objects.hash(sku, warehouse), POSITION_LOCK_STRIPES);
    }

    private <T> T withPosition(Sku sku, Warehouse warehouse, Supplier<T> action) {
        synchronized (positionLocks[positionLockIndex(sku, warehouse)]) {
            return action.get();
        }
    }

    /** 锁表当前占用槽位数（恒等于 {@link #POSITION_LOCK_STRIPES}，供回归测试断言锁表不增长）。 */
    int positionLockTableSize() {
        return positionLocks.length;
    }

    /** 当前水位（不存在则为零）。 */
    public StockPosition position(Sku sku, Warehouse warehouse) {
        return positionStore.getOrCreate(sku, warehouse);
    }

    /** 入库：在库存水位上增加 qty 并保存，返回新水位。 */
    public StockPosition stockIn(Sku sku, Warehouse warehouse, long qty) {
        log.info("stockIn sku={} warehouse={} qty={}", sku, warehouse, qty);
        return withPosition(
                sku,
                warehouse,
                () -> {
                    StockPosition p = positionStore.getOrCreate(sku, warehouse).receive(qty);
                    positionStore.save(p);
                    log.debug("stockIn 完成 sku={} warehouse={} 在库={}", sku, warehouse, p.onHand());
                    return p;
                });
    }

    /** 预占：按 reservationId 幂等；库存不足抛 OversellException；同 id 但参数不一致抛 ReservationConflictException。 */
    public Reservation reserve(
            String reservationId,
            StockyardTenant tenant,
            Sku sku,
            Warehouse warehouse,
            long qty,
            Long ttlSeconds) {
        log.info(
                "reserve reservationId={} sku={} warehouse={} qty={}",
                reservationId,
                sku,
                warehouse,
                qty);
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(warehouse, "warehouse");
        Optional<Reservation> existing = reservationStore.find(reservationId);
        if (existing.isPresent()) {
            Reservation e = existing.get();
            if (e.tenant().equals(tenant)
                    && e.sku().equals(sku)
                    && e.warehouse().equals(warehouse)
                    && e.qty() == qty) {
                // 幂等重放：与首次调用同参数时直接返回首次结果，不打 info 以免重放淹没日志。
                log.debug("reserve 幂等命中 reservationId={} status={}", reservationId, e.status());
                return e;
            }
            log.error(
                    "reserve 冲突 reservationId={} 已存在但参数不一致：存量 status={} qty={}，请求 qty={}",
                    reservationId,
                    e.status(),
                    e.qty(),
                    qty);
            throw new ReservationConflictException("reservationId 已存在但参数不一致：" + reservationId);
        }
        // 库存临界区：检查可用量→不足抛 OversellException→allocate→save，必须整体串行，避免超卖。
        withPosition(
                sku,
                warehouse,
                () -> {
                    StockPosition p = positionStore.getOrCreate(sku, warehouse);
                    if (p.available() < qty) {
                        throw new OversellException(
                                "库存不足：sku=" + sku + " 可用=" + p.available() + " 欲预占=" + qty);
                    }
                    StockPosition allocated = p.allocate(qty);
                    positionStore.save(allocated);
                    return null;
                });
        Reservation r =
                Reservation.pending(tenant, sku, warehouse, qty, ttlSeconds).toBuilder()
                        .id(reservationId)
                        .build()
                        .activate(Instant.now());
        Reservation saved = reservationStore.save(r);
        log.info(
                "reserve 成功 reservationId={} status={} sku={} warehouse={} qty={}",
                saved.id(),
                saved.status(),
                sku,
                warehouse,
                qty);
        return saved;
    }

    /** 确认预占并扣减库存；已确认则幂等返回；缺失抛 ReservationNotFoundException。 */
    public Reservation confirm(String reservationId, Instant now) {
        log.info("confirm reservationId={}", reservationId);
        Reservation r = require(reservationId);
        if (r.status() == ReservationStatus.CONFIRMED) {
            log.debug("confirm 幂等命中 reservationId={} 已确认", reservationId);
            return r;
        }
        Reservation confirmed = r.confirm(now);
        withPosition(
                r.sku(),
                r.warehouse(),
                () -> {
                    StockPosition p =
                            positionStore.getOrCreate(r.sku(), r.warehouse()).deduct(r.qty());
                    positionStore.save(p);
                    return null;
                });
        Reservation saved = reservationStore.save(confirmed);
        log.info(
                "confirm 状态迁移 {} -> {} reservationId={} qty={}",
                r.status(),
                saved.status(),
                reservationId,
                r.qty());
        return saved;
    }

    /** 取消预占并释放库存；已取消则幂等返回；已确认抛 ReservationException。 */
    public Reservation cancel(String reservationId, Instant now, String reason) {
        log.info("cancel reservationId={} reason={}", reservationId, reason);
        Reservation r = require(reservationId);
        if (r.status() == ReservationStatus.CANCELLED) {
            log.debug("cancel 幂等命中 reservationId={} 已取消", reservationId);
            return r;
        }
        Reservation cancelled = r.cancel(now, reason);
        withPosition(
                r.sku(),
                r.warehouse(),
                () -> {
                    StockPosition p =
                            positionStore.getOrCreate(r.sku(), r.warehouse()).release(r.qty());
                    positionStore.save(p);
                    return null;
                });
        Reservation saved = reservationStore.save(cancelled);
        log.info(
                "cancel 状态迁移 {} -> {} reservationId={} qty={} reason={}",
                r.status(),
                saved.status(),
                reservationId,
                r.qty(),
                reason);
        return saved;
    }

    /** 将到达 TTL 的 PENDING/ACTIVE 预占置为 EXPIRED 并释放库存，返回失效的 id 列表。 */
    public List<String> expireDue(Instant now) {
        log.info("expireDue now={}", now);
        List<String> expired = new ArrayList<>();
        for (Reservation r : reservationStore.findAll()) {
            if ((r.status() == ReservationStatus.PENDING || r.status() == ReservationStatus.ACTIVE)
                    && r.isExpired(now)) {
                Reservation expiredR = r.expire(now);
                withPosition(
                        r.sku(),
                        r.warehouse(),
                        () -> {
                            StockPosition p =
                                    positionStore
                                            .getOrCreate(r.sku(), r.warehouse())
                                            .release(r.qty());
                            positionStore.save(p);
                            return null;
                        });
                reservationStore.save(expiredR);
                expired.add(r.id());
            }
        }
        // 批量回收是循环操作：这里只打一条汇总结果，不在循环内逐条打日志。
        if (!expired.isEmpty()) {
            log.info("expireDue 回收 {} 条超时预占", expired.size());
        } else {
            log.debug("expireDue 无超时预占可回收");
        }
        return expired;
    }

    /** 幂等扣减：同一 requestId 重复调用返回首次结果；库存不足返回 FAILED 记录而非抛异常。 */
    public DeductionRecord deduct(
            String requestId,
            StockyardTenant tenant,
            Sku sku,
            Warehouse warehouse,
            long qty,
            Instant now) {
        log.info("deduct requestId={} sku={} warehouse={} qty={}", requestId, sku, warehouse, qty);
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(warehouse, "warehouse");
        Optional<DeductionRecord> existing = deductionStore.find(requestId);
        if (existing.isPresent()) {
            log.debug("deduct 幂等命中 requestId={} status={}", requestId, existing.get().status());
            return existing.get();
        }
        // 库存临界区：检查可用量→不足记 failed→consume→save，必须整体串行，避免并发超扣。
        DeductionRecord rec =
                withPosition(
                        sku,
                        warehouse,
                        () -> {
                            StockPosition p = positionStore.getOrCreate(sku, warehouse);
                            if (p.available() < qty) {
                                // 库存不足不算系统故障，但属于必须可见的业务降级：返回 FAILED 记录而非
                                // 抛异常，调用方容易忽略，所以这里打一条 warn 留下证据。
                                log.warn(
                                        "deduct 库存不足 requestId={} sku={} warehouse={} 可用={} 欲扣减={}",
                                        requestId,
                                        sku,
                                        warehouse,
                                        p.available(),
                                        qty);
                                return DeductionRecord.failed(
                                        requestId,
                                        tenant,
                                        sku,
                                        warehouse,
                                        qty,
                                        "库存不足：可用=" + p.available());
                            }
                            StockPosition after = p.consume(qty);
                            positionStore.save(after);
                            return DeductionRecord.success(requestId, tenant, sku, warehouse, qty);
                        });
        deductionStore.save(rec);
        log.info("deduct 结果 requestId={} status={}", rec.requestId(), rec.status());
        return rec;
    }

    private Reservation require(String reservationId) {
        return reservationStore
                .find(reservationId)
                .orElseThrow(
                        () -> {
                            // confirm/cancel 的公共入口：预占缺失是调用方错误，记一条 warn 便于定位，
                            // 异常照旧向上抛，行为不变。
                            log.warn("预占不存在 reservationId={}", reservationId);
                            return new ReservationNotFoundException("预占不存在：" + reservationId);
                        });
    }
}
