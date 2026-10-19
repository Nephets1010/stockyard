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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次幂等扣减的不可变记录，用于按 requestId 去重。
 *
 * <p><b>幂等键的语义</b>：{@code requestId} 由<b>调用方</b>生成并保证「同一次业务意图 = 同一个 id」 （通常用订单号 + 行号，或上游消息
 * id）。引擎在扣减前先按它查记录：查到就直接返回上次的结论， 绝不重复扣库存。因此幂等的粒度完全由调用方决定——同一个订单行重发 10 次只扣 1 次，10 个不同 订单行各扣 1 次。
 *
 * <p><b>为什么库存不足也要写一条 FAILED 记录而不是直接抛异常</b>：抛异常意味着调用方必须立刻处理， 而「库存不足」在秒杀场景里是常态而非故障；同时如果不落记录，下一次同
 * requestId 重试会重新走一遍 判定，在库存被补货后可能产生「上次失败、这次成功」的不一致结果。落 FAILED 记录把「这个请求已经
 * 判过、结论是不可用」固化下来，重试得到的是稳定答案。代价是 FAILED 记录会长期占位，因此本库 要求调用方对 requestId 设合理有效期（见 {@code
 * DeductionStore} 实现类的容量上限）。
 *
 * <p><b>为什么 equals/hashCode 只看 requestId</b>：同一 requestId 的两条记录代表同一次业务意图， 无论状态、数量是否相同都只应保留一条。把 qty
 * / status 加进 equals 会让「先失败后成功」变成两条 并存记录，去重就失效了。
 */
public final class DeductionRecord {

    private static final Logger log = LoggerFactory.getLogger(DeductionRecord.class);

    private final String requestId;
    private final StockyardTenant tenant;
    private final Sku sku;
    private final Warehouse warehouse;
    private final long qty;
    private final DeductionStatus status;
    private final Instant createdAt;
    private final String error;

    private DeductionRecord(Builder b) {
        this.requestId = b.requestId;
        this.tenant = b.tenant;
        this.sku = b.sku;
        this.warehouse = b.warehouse;
        this.qty = b.qty;
        this.status = b.status;
        this.createdAt = b.createdAt;
        this.error = b.error;
    }

    /** 构造一条成功记录，时间取系统时钟 {@code Clock.systemUTC()}。 */
    public static DeductionRecord success(
            String requestId, StockyardTenant tenant, Sku sku, Warehouse warehouse, long qty) {
        return success(requestId, tenant, sku, warehouse, qty, Clock.systemUTC());
    }

    /**
     * 构造一条成功记录，时间取 caller 传入的时钟。
     *
     * <p>为什么成功/失败记录都要带 {@code createdAt} 且要能注入时钟：幂等记录的有效期（多久之后可以
     * 清理）与对账（这条记录是何时产生的）都依赖它。用系统时钟写入的话，任何依赖时间的断言都只能 用「大于等于某个近似值」这种弱断言；注入时钟后可以断言精确时间戳。
     */
    public static DeductionRecord success(
            String requestId,
            StockyardTenant tenant,
            Sku sku,
            Warehouse warehouse,
            long qty,
            Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return builder()
                .requestId(requestId)
                .tenant(tenant)
                .sku(sku)
                .warehouse(warehouse)
                .qty(qty)
                .status(DeductionStatus.SUCCESS)
                .createdAt(clock.instant())
                .build();
    }

    /**
     * 构造一条失败记录，error 描述原因。
     *
     * <p>失败记录是「库存不足不抛异常」的降级出口，调用方很容易只判 status 而不看 error，这里补一条 debug 兜底（具体原因由 {@code
     * StockyardEngine} 在扣减边界 warn 一次，避免重复刷日志）。
     */
    public static DeductionRecord failed(
            String requestId,
            StockyardTenant tenant,
            Sku sku,
            Warehouse warehouse,
            long qty,
            String error) {
        return failed(requestId, tenant, sku, warehouse, qty, error, Clock.systemUTC());
    }

    /** 构造一条失败记录，时间取 caller 传入的时钟。 */
    public static DeductionRecord failed(
            String requestId,
            StockyardTenant tenant,
            Sku sku,
            Warehouse warehouse,
            long qty,
            String error,
            Clock clock) {
        Objects.requireNonNull(clock, "clock");
        log.debug(
                "扣减失败记录 requestId={} sku={} warehouse={} qty={} error={}",
                requestId,
                sku,
                warehouse,
                qty,
                error);
        return builder()
                .requestId(requestId)
                .tenant(tenant)
                .sku(sku)
                .warehouse(warehouse)
                .qty(qty)
                .status(DeductionStatus.FAILED)
                .createdAt(clock.instant())
                .error(error)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String requestId() {
        return requestId;
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

    public DeductionStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String error() {
        return error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeductionRecord)) {
            return false;
        }
        return Objects.equals(requestId, ((DeductionRecord) o).requestId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId);
    }

    @Override
    public String toString() {
        return "DeductionRecord{" + requestId + "," + status + "}";
    }

    /** 扣减记录构造器。 */
    public static final class Builder {
        private String requestId;
        private StockyardTenant tenant;
        private Sku sku;
        private Warehouse warehouse;
        private long qty;
        private DeductionStatus status;
        private Instant createdAt;
        private String error;

        public Builder requestId(String v) {
            this.requestId = v;
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

        public Builder status(DeductionStatus v) {
            this.status = v;
            return this;
        }

        public Builder createdAt(Instant v) {
            this.createdAt = v;
            return this;
        }

        public Builder error(String v) {
            this.error = v;
            return this;
        }

        public DeductionRecord build() {
            // build() 是外部还原扣减记录的唯一入口；requestId 缺失会让幂等去重彻底失效，必须留痕。
            if (requestId == null) {
                log.warn("构造扣减记录被拒：requestId 必填");
                throw new IllegalArgumentException("requestId 必填");
            }
            if (tenant == null) {
                log.warn("构造扣减记录被拒：tenant 必填 requestId={}", requestId);
                throw new IllegalArgumentException("tenant 必填");
            }
            if (sku == null) {
                log.warn("构造扣减记录被拒：sku 必填 requestId={}", requestId);
                throw new IllegalArgumentException("sku 必填");
            }
            if (warehouse == null) {
                log.warn("构造扣减记录被拒：warehouse 必填 requestId={}", requestId);
                throw new IllegalArgumentException("warehouse 必填");
            }
            if (status == null) {
                log.warn("构造扣减记录被拒：status 必填 requestId={}", requestId);
                throw new IllegalArgumentException("status 必填");
            }
            if (qty <= 0) {
                log.warn("构造扣减记录被拒：qty 必须为正 requestId={} qty={}", requestId, qty);
                throw new IllegalArgumentException("qty 必须为正");
            }
            return new DeductionRecord(this);
        }
    }
}
