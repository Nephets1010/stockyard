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
package io.github.nephets.stockyard.core.reservation;

import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次库存预占。不可变，状态流转返回新副本。
 *
 * <h2>状态机</h2>
 *
 * <pre>
 *   pending --activate--> ACTIVE --confirm--> CONFIRMED（终态）
 *      |                    |
 *      +----cancel----------+----cancel--> CANCELLED（终态）
 *      |                    |
 *      +----expire----------+----expire--> EXPIRED（终态）
 * </pre>
 *
 * <p><b>为什么是「预占—确认」两段式而不是直接扣减</b>：电商下单到支付之间有一段时间窗口，若下单即扣减，
 * 未支付的订单会长时间占住库存（少卖）；若不占库存直接扣，支付时才发现没货（超卖）。两段式把库存从 「在库 onHand」里划出一块「已预占 allocated」，可用量 = onHand -
 * allocated，既让未支付订单不阻塞 真实销售（TTL 到期自动回收），又保证已支付的订单一定有货。
 *
 * <p><b>为什么状态迁移方法返回新副本而不是就地修改</b>：预占单会被 {@code ReservationStore} 缓存、 被 TTL
 * 扫描线程遍历、被业务线程读取。就地修改会让「读到的对象」与「存进去的对象」不是同一状态， 出现「看到已确认、实际还占着库存」这类无法复现的问题。不可变 + 迁移返回新副本，使得任何一次读取
 * 都是一个完整一致的状态快照，也天然线程安全（不需要额外同步）。
 *
 * <p><b>为什么每个非法迁移分支都要打日志</b>：能调用到 {@code confirm(ACTIVE 之外)} 说明调用方绕过了
 * 引擎直接改状态，或存在并发路径没被锁覆盖。这是「状态机被绕过」的信号而不是普通的业务失败，必须 留下证据；而它是低频失败路径，不在热循环上，打日志的代价可以接受。
 *
 * <h2>时间</h2>
 *
 * <p>所有需要「现在几点」的判定都通过 {@code Clock} 取时间，且提供显式传 {@link java.time.Clock} 的 重载。为什么：TTL
 * 过期、超时释放这类逻辑只有能被确定性推进时间才可测——用 {@code Instant.now()} 的写法只能靠 {@code Thread.sleep} 等待，测试既慢又不稳定（CI
 * 繁忙时会假失败）。注入 {@code Clock} 后，测试用固定时钟即可把「30 分钟后」压缩成一次方法调用。
 */
public final class Reservation {

    private static final Logger log = LoggerFactory.getLogger(Reservation.class);

    private final String id;
    private final StockyardTenant tenant;
    private final Sku sku;
    private final Warehouse warehouse;
    private final long qty;
    private final ReservationStatus status;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant confirmedAt;
    private final Instant cancelledAt;
    private final Instant expiredAt;
    private final String cancelReason;

    private Reservation(Builder b) {
        this.id = b.id;
        this.tenant = b.tenant;
        this.sku = b.sku;
        this.warehouse = b.warehouse;
        this.qty = b.qty;
        this.status = b.status;
        this.createdAt = b.createdAt;
        this.expiresAt = b.expiresAt;
        this.confirmedAt = b.confirmedAt;
        this.cancelledAt = b.cancelledAt;
        this.expiredAt = b.expiredAt;
        this.cancelReason = b.cancelReason;
    }

    /**
     * 创建一条待生效预占；ttlSeconds 为 null 表示不过期。时间取系统时钟 {@code Clock.systemUTC()}。
     *
     * @see #pending(StockyardTenant, Sku, Warehouse, long, Long, Clock)
     */
    public static Reservation pending(
            StockyardTenant tenant, Sku sku, Warehouse warehouse, long qty, Long ttlSeconds) {
        return pending(tenant, sku, warehouse, qty, ttlSeconds, Clock.systemUTC());
    }

    /**
     * 创建一条待生效预占；时间取 caller 传入的时钟。
     *
     * <p>为什么把 {@code Clock} 一路传到这里：{@code createdAt} 与 {@code expiresAt} 决定了这条预占 什么时候被 {@code
     * expireDue} 回收。用系统时钟的话，「下单后 30 分钟未支付自动释放」这条规则 只能靠真实等待来验证；注入时钟后测试可以把时钟往前拨 31 分钟，毫秒级完成同样的验证。
     */
    public static Reservation pending(
            StockyardTenant tenant,
            Sku sku,
            Warehouse warehouse,
            long qty,
            Long ttlSeconds,
            Clock clock) {
        Objects.requireNonNull(clock, "clock");
        if (qty <= 0) {
            log.warn("创建预占被拒：数量必须为正，实际 qty={}", qty);
            throw new IllegalArgumentException("预占数量必须为正：" + qty);
        }
        Instant now = clock.instant();
        Instant expires = ttlSeconds == null ? null : now.plusSeconds(ttlSeconds);
        return builder()
                .id(UUID.randomUUID().toString())
                .tenant(tenant)
                .sku(sku)
                .warehouse(warehouse)
                .qty(qty)
                .status(ReservationStatus.PENDING)
                .createdAt(now)
                .expiresAt(expires)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() {
        return id;
    }

    public StockyardTenant tenant() {
        return tenant;
    }

    public Sku sku() {
        return sku;
    }

    public Warehouse warehouse() {
        return warehouse;
    }

    public long qty() {
        return qty;
    }

    public ReservationStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public Instant expiredAt() {
        return expiredAt;
    }

    public String cancelReason() {
        return cancelReason;
    }

    /** 是否已过 TTL（仅当设置了过期时间）。 */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /**
     * 生效：PENDING -> ACTIVE。已过期或状态不符抛 ReservationException。
     *
     * <p>为什么只在被拒绝的分支打日志：成功迁移由 {@code StockyardEngine} 在业务入口统一记录；而
     * 非法迁移是「状态机被绕过」的信号，必须留下证据，且它是失败路径，不在热循环上。
     */
    public Reservation activate(Instant now) {
        if (status != ReservationStatus.PENDING) {
            log.warn("预占生效被拒 id={} 当前状态={} 期望=PENDING", id, status);
            throw new ReservationException("只能在 PENDING 状态生效，当前=" + status);
        }
        if (isExpired(now)) {
            log.warn("预占生效被拒 id={} 已过 TTL expiresAt={} now={}", id, expiresAt, now);
            throw new ReservationException("预占已过期，无法生效：" + id);
        }
        return toBuilder().status(ReservationStatus.ACTIVE).build();
    }

    /** 确认：ACTIVE -> CONFIRMED。 */
    public Reservation confirm(Instant now) {
        if (status != ReservationStatus.ACTIVE) {
            log.warn("预占确认被拒 id={} 当前状态={} 期望=ACTIVE", id, status);
            throw new ReservationException("只能在 ACTIVE 状态确认，当前=" + status);
        }
        return toBuilder().status(ReservationStatus.CONFIRMED).confirmedAt(now).build();
    }

    /** 取消：PENDING/ACTIVE -> CANCELLED，释放库存。 */
    public Reservation cancel(Instant now, String reason) {
        if (status == ReservationStatus.CONFIRMED) {
            log.warn("预占取消被拒 id={} 已确认不可取消", id);
            throw new ReservationException("已确认不可取消：" + id);
        }
        if (status == ReservationStatus.CANCELLED) {
            log.warn("预占取消被拒 id={} 已取消不可重复取消", id);
            throw new ReservationException("已取消不可重复取消：" + id);
        }
        if (status == ReservationStatus.EXPIRED) {
            log.warn("预占取消被拒 id={} 已过期不可取消", id);
            throw new ReservationException("已过期不可取消：" + id);
        }
        return toBuilder()
                .status(ReservationStatus.CANCELLED)
                .cancelledAt(now)
                .cancelReason(reason)
                .build();
    }

    /** 超时失效：PENDING/ACTIVE -> EXPIRED，释放库存。 */
    public Reservation expire(Instant now) {
        if (status == ReservationStatus.CONFIRMED) {
            log.warn("预占超时被拒 id={} 已确认不可超时", id);
            throw new ReservationException("已确认不可超时：" + id);
        }
        if (status == ReservationStatus.CANCELLED) {
            log.warn("预占超时被拒 id={} 已取消不可超时", id);
            throw new ReservationException("已取消不可超时：" + id);
        }
        if (status == ReservationStatus.EXPIRED) {
            log.warn("预占超时被拒 id={} 已超时不可重复超时", id);
            throw new ReservationException("已超时不可重复超时：" + id);
        }
        return toBuilder().status(ReservationStatus.EXPIRED).expiredAt(now).build();
    }

    public Builder toBuilder() {
        return builder()
                .id(id)
                .tenant(tenant)
                .sku(sku)
                .warehouse(warehouse)
                .qty(qty)
                .status(status)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .confirmedAt(confirmedAt)
                .cancelledAt(cancelledAt)
                .expiredAt(expiredAt)
                .cancelReason(cancelReason);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Reservation)) {
            return false;
        }
        Reservation r = (Reservation) o;
        return qty == r.qty
                && status == r.status
                && Objects.equals(id, r.id)
                && Objects.equals(tenant, r.tenant)
                && Objects.equals(sku, r.sku)
                && Objects.equals(warehouse, r.warehouse);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tenant, sku, warehouse, qty, status);
    }

    @Override
    public String toString() {
        return "Reservation{" + id + "," + status + ",qty=" + qty + "}";
    }

    /** 预占构造器。 */
    public static final class Builder {
        private String id;
        private StockyardTenant tenant;
        private Sku sku;
        private Warehouse warehouse;
        private long qty;
        private ReservationStatus status;
        private Instant createdAt;
        private Instant expiresAt;
        private Instant confirmedAt;
        private Instant cancelledAt;
        private Instant expiredAt;
        private String cancelReason;

        public Builder id(String v) {
            this.id = v;
            return this;
        }

        public Builder tenant(StockyardTenant v) {
            this.tenant = v;
            return this;
        }

        public Builder sku(Sku v) {
            this.sku = v;
            return this;
        }

        public Builder warehouse(Warehouse v) {
            this.warehouse = v;
            return this;
        }

        public Builder qty(long v) {
            this.qty = v;
            return this;
        }

        public Builder status(ReservationStatus v) {
            this.status = v;
            return this;
        }

        public Builder createdAt(Instant v) {
            this.createdAt = v;
            return this;
        }

        public Builder expiresAt(Instant v) {
            this.expiresAt = v;
            return this;
        }

        public Builder confirmedAt(Instant v) {
            this.confirmedAt = v;
            return this;
        }

        public Builder cancelledAt(Instant v) {
            this.cancelledAt = v;
            return this;
        }

        public Builder expiredAt(Instant v) {
            this.expiredAt = v;
            return this;
        }

        public Builder cancelReason(String v) {
            this.cancelReason = v;
            return this;
        }

        public Reservation build() {
            // build() 是外部构造预占的唯一入口，字段缺失属于调用方错误，必须留下可定位的日志。
            if (id == null) {
                log.warn("构造预占被拒：id 必填");
                throw new IllegalArgumentException("id 必填");
            }
            if (tenant == null) {
                log.warn("构造预占被拒：tenant 必填 id={}", id);
                throw new IllegalArgumentException("tenant 必填");
            }
            if (sku == null) {
                log.warn("构造预占被拒：sku 必填 id={}", id);
                throw new IllegalArgumentException("sku 必填");
            }
            if (warehouse == null) {
                log.warn("构造预占被拒：warehouse 必填 id={}", id);
                throw new IllegalArgumentException("warehouse 必填");
            }
            if (status == null) {
                log.warn("构造预占被拒：status 必填 id={}", id);
                throw new IllegalArgumentException("status 必填");
            }
            if (qty <= 0) {
                log.warn("构造预占被拒：qty 必须为正 id={} qty={}", id, qty);
                throw new IllegalArgumentException("qty 必须为正");
            }
            return new Reservation(this);
        }
    }
}
