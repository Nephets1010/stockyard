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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 库存引擎：把预占状态机、库存水位与幂等扣减串起来。
 *
 * <h2>防超卖是怎么保证的</h2>
 *
 * <p>所有会改库存水位的动作（入库 / 预占 / 确认 / 取消 / 超时释放 / 直接扣减）在写回之前都必须走 「读水位 → 算出新副本 → save」三步。这三步不是原子的：两个线程同时读到
 * {@code available=1}， 各自算出 {@code available=0} 再写回，一次库存就被卖了两遍。因此引擎对同一个 (sku, warehouse) 的这三步用
 * {@link #withPosition} 串行化，任何时刻只有一个线程能改同一个 (sku, warehouse) 的水位。 这就是「不超卖」的全部实现——没有分布式锁，也没有依赖存储层的
 * CAS。
 *
 * <p>代价与边界：这把锁是 JVM 内的 {@code synchronized}，多实例部署时它保护不了跨进程并发（见 README
 * 的「已知局限」）。要跨节点保证，正确做法是把「读—算—写」下沉成存储层的一次原子操作（Redis Lua / DB 的 {@code UPDATE ... WHERE on_hand -
 * allocated >= ?}），而不是把这里的锁换成分布式锁——后者 会把「一次库存操作」变成「一次网络往返 + 一次锁租约」，吞吐下降一个数量级。
 *
 * <h2>时间</h2>
 *
 * <p>所有「现在几点」都来自构造期注入的 {@link Clock}。为什么：预占 TTL 到期回收是唯一依赖时间的 业务规则，用 {@code Instant.now()} 写的话，测试只能
 * {@code Thread.sleep(31分钟)} 或者把 TTL 调成 几百毫秒去赌调度——前者慢到没法进 CI，后者在机器繁忙时随机失败。注入时钟后，测试用 {@code
 * Clock.fixed} 一次推进 31 分钟，断言就变成确定性的。
 */
public final class StockyardEngine {

    private static final Logger log = LoggerFactory.getLogger(StockyardEngine.class);

    /**
     * per-(sku,warehouse) 锁的分片数。
     *
     * <p><b>为什么是定长分片而不是「按需建锁 + 用后淘汰」</b>：按需建的锁表条目数等于出现过的 (sku,warehouse)
     * 组合数，是长生命周期的无界集合，SKU×仓库规模一大就是内存泄漏；而对锁做 LRU 淘汰又更危险——同一 key
     * 的锁被淘汰后，新请求会拿到<b>另一把</b>锁，「读水位→改副本→save」就不再 串行，直接退化成超卖。定长分片锁构造后容量恒定（天然有上限），且同一 key 恒定映射到同一把锁，
     * 互斥语义不变，代价只是不同 key 可能共用一把锁、并行度略降——这是安全方向上的取舍。
     *
     * <p><b>为什么取 64</b>：三个理由。①它是 2 的幂，{@code floorMod(hash, 64)} 等价于 {@code hash & 63}
     * 的位运算，取模不必走除法；②本引擎的目标并发度是「几十个线程同时打不同 SKU」， 64 个槽在无锁竞争时足够把冲突概率压到很低（64 个热点 SKU 均匀分布时碰撞概率约 1/64）；
     * ③锁数组本身要常驻内存，64 × 对象头（16 B）≈ 1 KB，再往上翻（1024、4096）换来的并行度提升 已经不明显，却白白增加伪共享与 GC 扫描成本。若你的热点 SKU
     * 只有个位数，64 依然是安全的： 分片过多不会破坏正确性，只会浪费一点内存。
     *
     * <p><b>为什么用 {@code Objects.hash(sku, warehouse)} 而不是 {@code hashCode() % 64}</b>： {@code
     * Sku.hashCode()} 与 {@code Warehouse.hashCode()} 各自基于 {@code Objects.hash(code)}，
     * 对短字符串而言分布已经够均匀；但直接用 {@code sku.hashCode()} 会把「同 SKU 不同仓库」全部打到 同一个槽，而真实库存系统里同 SKU
     * 多仓是常态。把两者一起哈希能让同一 SKU 的不同仓库分散到不同 锁上，并行度显著提高。同理必须 {@code Math.floorMod}：{@code hashCode()}
     * 可能为负， {@code % 64} 会返回负数索引直接越界。
     */
    static final int POSITION_LOCK_STRIPES = 64;

    private final ReservationStore reservationStore;
    private final StockPositionStore positionStore;
    private final DeductionStore deductionStore;
    private final Clock clock;

    // 同一 (sku,warehouse) 的库存水位在并发预占/确认/取消/扣减/入库时必须串行，否则「读水位→
    // 生成新副本→save」的 check-then-act 会超卖。用 per-key 锁把临界区串行化；不同 (sku,warehouse)
    // 之间仍可并发。生产环境更推荐把原子更新下沉到存储层（如 CAS），此处保持对 StockPositionStore
    // 实现的零侵入。
    private final Object[] positionLocks = new Object[POSITION_LOCK_STRIPES];

    /** 使用系统时钟构造。 */
    public StockyardEngine(
            ReservationStore reservationStore,
            StockPositionStore positionStore,
            DeductionStore deductionStore) {
        this(reservationStore, positionStore, deductionStore, Clock.systemUTC());
    }

    /**
     * 使用指定时钟构造。
     *
     * <p>为什么时钟必须在构造期注入而不是在方法上默认取 {@code Instant.now()}：时钟是引擎的<b>全局
     * 事实来源</b>，同一个引擎实例内部若一半方法用注入时钟、一半用系统时钟，TTL 判定就会自相矛盾
     * （用注入时钟创建的预占，被系统时钟判定过期）。构造期一次性钉死可以保证实例内部时间观一致。
     */
    public StockyardEngine(
            ReservationStore reservationStore,
            StockPositionStore positionStore,
            DeductionStore deductionStore,
            Clock clock) {
        this.reservationStore = Objects.requireNonNull(reservationStore, "reservationStore");
        this.positionStore = Objects.requireNonNull(positionStore, "positionStore");
        this.deductionStore = Objects.requireNonNull(deductionStore, "deductionStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (int i = 0; i < POSITION_LOCK_STRIPES; i++) {
            positionLocks[i] = new Object();
        }
    }

    /**
     * 本引擎使用的时钟。
     *
     * <p>为什么暴露它：调用方（如 starter 的定时任务）需要按同一个时间观推进 TTL 扫描；测试也需要 确认「引擎确实在用我注入的时钟」而不是偷偷回退到系统时钟。
     */
    public Clock clock() {
        return clock;
    }

    /**
     * (sku,warehouse) 落在哪个锁槽。
     *
     * <p>包内可见而非 private：并发回归测试需要构造「故意撞槽」与「故意不撞槽」两组 key， 才能分别覆盖「互斥生效」与「并行不阻塞」两条路径——只测后者等于没测防超卖。
     */
    static int positionLockIndex(Sku sku, Warehouse warehouse) {
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

    /**
     * 预占：按 reservationId 幂等；库存不足抛 OversellException；同 id 但参数不一致抛 ReservationConflictException。
     *
     * <p><b>为什么同 id 参数不一致要抛冲突而不是覆盖</b>：reservationId 由调用方生成，代表「同一次 业务意图」。如果同一个 id 带着不同 qty
     * 再次提交还能静默成功，说明调用方要么复用了 id（bug）， 要么被重放了恶意请求。静默覆盖会让「我以为占了 1 件、实际占了 100 件」这种错误无法被发现； 抛 {@code
     * ReservationConflictException} 把冲突暴露在第一次发生时，是唯一安全的选择。
     *
     * <p><b>为什么连「查重放」也在锁内</b>：把幂等查询放在锁外确实更快，但两个线程就会同时查不到 记录、各自判定为首次，同一 reservationId 被 allocate
     * 两次——结果是库存被占住 2 份，却只有 1 条预占单记得住，那 1 份永远释放不掉。锁的粒度必须覆盖完整的 check-then-act：从「查有没有」
     * 一直到「落下预占单」。这里宁可让重放流量也走一次锁，也不接受库存泄漏。
     */
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
        // 整个 reserve 是一个临界区：查重放、查可用量、allocate、落预占单，四步必须整体串行。
        return withPosition(
                sku,
                warehouse,
                () -> {
                    Optional<Reservation> existing = reservationStore.find(reservationId);
                    if (existing.isPresent()) {
                        Reservation e = existing.get();
                        if (e.tenant().equals(tenant)
                                && e.sku().equals(sku)
                                && e.warehouse().equals(warehouse)
                                && e.qty() == qty) {
                            // 幂等重放：与首次调用同参数时直接返回首次结果，不打 info 以免重放淹没日志。
                            log.debug(
                                    "reserve 幂等命中 reservationId={} status={}",
                                    reservationId,
                                    e.status());
                            return e;
                        }
                        log.error(
                                "reserve 冲突 reservationId={} 已存在但参数不一致：存量 status={} qty={}，请求 qty={}",
                                reservationId,
                                e.status(),
                                e.qty(),
                                qty);
                        throw new ReservationConflictException(
                                "reservationId 已存在但参数不一致：" + reservationId);
                    }
                    StockPosition p = positionStore.getOrCreate(sku, warehouse);
                    if (p.available() < qty) {
                        throw new OversellException(
                                "库存不足：sku=" + sku + " 可用=" + p.available() + " 欲预占=" + qty);
                    }
                    StockPosition allocated = p.allocate(qty);
                    positionStore.save(allocated);
                    Instant now = clock.instant();
                    Reservation r =
                            Reservation.pending(tenant, sku, warehouse, qty, ttlSeconds, clock)
                                    .toBuilder()
                                    .id(reservationId)
                                    .build()
                                    .activate(now);
                    Reservation saved = reservationStore.save(r);
                    log.info(
                            "reserve 成功 reservationId={} status={} sku={} warehouse={} qty={}",
                            saved.id(),
                            saved.status(),
                            sku,
                            warehouse,
                            qty);
                    return saved;
                });
    }

    /** 确认预占并扣减库存；时间戳取注入时钟。 */
    public Reservation confirm(String reservationId) {
        return confirm(reservationId, clock.instant());
    }

    /** 确认预占并扣减库存；已确认则幂等返回；缺失抛 ReservationNotFoundException。 */
    public Reservation confirm(String reservationId, Instant now) {
        log.info("confirm reservationId={}", reservationId);
        // 先取一次只为拿到 (sku,warehouse) 去选锁——预占单的 sku/warehouse 一经创建就不再变，
        // 用它选锁是稳定的。真正的状态判定与库存扣减必须在锁内重做一遍。
        Reservation key = require(reservationId);
        return withPosition(
                key.sku(),
                key.warehouse(),
                () -> {
                    Reservation r = require(reservationId);
                    if (r.status() == ReservationStatus.CONFIRMED) {
                        log.debug("confirm 幂等命中 reservationId={} 已确认", reservationId);
                        return r;
                    }
                    // 「状态判定 → 扣库存 → 落状态」必须在同一临界区内，否则两个线程同时确认
                    // 同一个预占会各自通过判定、各扣一次，库存凭空少掉一份。
                    Reservation confirmed = r.confirm(now);
                    StockPosition p =
                            positionStore.getOrCreate(r.sku(), r.warehouse()).deduct(r.qty());
                    positionStore.save(p);
                    Reservation saved = reservationStore.save(confirmed);
                    log.info(
                            "confirm 状态迁移 {} -> {} reservationId={} qty={}",
                            r.status(),
                            saved.status(),
                            reservationId,
                            r.qty());
                    return saved;
                });
    }

    /** 取消预占并释放库存；时间戳取注入时钟。 */
    public Reservation cancel(String reservationId, String reason) {
        return cancel(reservationId, clock.instant(), reason);
    }

    /** 取消预占并释放库存；已取消则幂等返回；已确认抛 ReservationException。 */
    public Reservation cancel(String reservationId, Instant now, String reason) {
        log.info("cancel reservationId={} reason={}", reservationId, reason);
        Reservation key = require(reservationId);
        return withPosition(
                key.sku(),
                key.warehouse(),
                () -> {
                    Reservation r = require(reservationId);
                    if (r.status() == ReservationStatus.CANCELLED) {
                        log.debug("cancel 幂等命中 reservationId={} 已取消", reservationId);
                        return r;
                    }
                    // 与 confirm 同理：取消也必须整体串行，否则重复取消会把库存释放多次（凭空造货）。
                    Reservation cancelled = r.cancel(now, reason);
                    StockPosition p =
                            positionStore.getOrCreate(r.sku(), r.warehouse()).release(r.qty());
                    positionStore.save(p);
                    Reservation saved = reservationStore.save(cancelled);
                    log.info(
                            "cancel 状态迁移 {} -> {} reservationId={} qty={} reason={}",
                            r.status(),
                            saved.status(),
                            reservationId,
                            r.qty(),
                            reason);
                    return saved;
                });
    }

    /** 将到达 TTL 的 PENDING/ACTIVE 预占置为 EXPIRED 并释放库存；「现在」取注入时钟。 */
    public List<String> expireDue() {
        return expireDue(clock.instant());
    }

    /** 将到达 TTL 的 PENDING/ACTIVE 预占置为 EXPIRED 并释放库存，返回失效的 id 列表。 */
    public List<String> expireDue(Instant now) {
        log.info("expireDue now={}", now);
        List<String> expired = new ArrayList<>();
        for (Reservation r : reservationStore.findAll()) {
            if ((r.status() == ReservationStatus.PENDING || r.status() == ReservationStatus.ACTIVE)
                    && r.isExpired(now)) {
                // TTL 扫描会与 confirm/cancel 抢同一条预占：整段放进锁内，保证「谁先到谁生效」，
                // 后到的一方会因状态已变而被状态机拒绝，绝不会二次释放库存。
                withPosition(
                        r.sku(),
                        r.warehouse(),
                        () -> {
                            Reservation current = require(r.id());
                            if (!(current.status() == ReservationStatus.PENDING
                                    || current.status() == ReservationStatus.ACTIVE)) {
                                return null;
                            }
                            Reservation expiredR = current.expire(now);
                            StockPosition p =
                                    positionStore
                                            .getOrCreate(r.sku(), r.warehouse())
                                            .release(r.qty());
                            positionStore.save(p);
                            reservationStore.save(expiredR);
                            expired.add(r.id());
                            return null;
                        });
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

    /**
     * 幂等扣减：同一 requestId 重复调用返回首次结果；库存不足返回 FAILED 记录而非抛异常。
     *
     * <p><b>幂等为什么要放在扣减路径上</b>：库存扣减通常由「支付成功」这类消息触发，而消息中间件只 保证至少一次投递。若扣减不幂等，一次重试就等于多卖一件。因此这里把
     * requestId 当作业务意图的 唯一标识：先查记录，查到就直接返回上次结论，<b>连库存水位都不读</b>——这样既避免了重复扣减， 也让「重试风暴」的成本退化成一次 map 查找。
     *
     * <p><b>为什么幂等判定必须和库存扣减在同一个临界区内</b>：把「查 requestId」放在锁外确实更快
     * （重放流量不用抢锁），但那样两个线程会同时查不到记录、各自判定为首次，同一笔业务被扣两次。 库存是钱，正确性优先于这点吞吐：整个 deduct 放进 (sku,warehouse)
     * 的分片锁里，重放流量因此 只会付出一次 map 查找 + 一次锁获取，仍然是 O(1)。
     *
     * <p><b>幂等的边界</b>：本实现保证「同一 requestId 在同一 JVM 内只扣一次」。跨进程部署时， 分片锁只覆盖本进程，两个节点同时首次提交同一 requestId
     * 仍会各扣一次——要跨节点成立， 必须让 {@code DeductionStore} 提供 put-if-absent 原子语义（Redis {@code SETNX} / DB
     * 唯一索引），而不是靠这里的锁。
     */
    public DeductionRecord deduct(
            String requestId, StockyardTenant tenant, Sku sku, Warehouse warehouse, long qty) {
        return deduct(requestId, tenant, sku, warehouse, qty, clock.instant());
    }

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
        // 库存 + 幂等临界区：查 requestId → 查可用量 → 不足记 failed → consume → save，
        // 必须整体串行，否则并发首次提交同一 requestId 会重复扣减。
        DeductionRecord rec =
                withPosition(
                        sku,
                        warehouse,
                        () -> {
                            Optional<DeductionRecord> existing = deductionStore.find(requestId);
                            if (existing.isPresent()) {
                                log.debug(
                                        "deduct 幂等命中 requestId={} status={}",
                                        requestId,
                                        existing.get().status());
                                return existing.get();
                            }
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
                                DeductionRecord failed =
                                        DeductionRecord.failed(
                                                requestId,
                                                tenant,
                                                sku,
                                                warehouse,
                                                qty,
                                                "库存不足：可用=" + p.available(),
                                                clock);
                                deductionStore.save(failed);
                                return failed;
                            }
                            StockPosition after = p.consume(qty);
                            positionStore.save(after);
                            DeductionRecord success =
                                    DeductionRecord.success(
                                            requestId, tenant, sku, warehouse, qty, clock);
                            // 记账必须也在临界区内：若把 save 留到锁外，两个线程会各自查不到记录、
                            // 各自判定为首次，同一 requestId 被扣两次（资损）。失败记录同理。
                            deductionStore.save(success);
                            return success;
                        });
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
