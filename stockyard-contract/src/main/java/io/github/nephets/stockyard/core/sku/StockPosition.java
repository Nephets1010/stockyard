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
package io.github.nephets.stockyard.core.sku;

import io.github.nephets.stockyard.core.OversellException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 某个仓库内某个 SKU 的库存水位。不可变，所有变更返回新副本。
 *
 * <p>可观测约定：本类的成功路径（receive/allocate/release/deduct/consume）是库存热路径，刻意不打日志——
 * 一次秒杀里这些方法会被逐条调用，逐条打点会淹没日志并拖慢扣减。成功迁移由 {@code StockyardEngine} 与 {@code StockyardController}
 * 在业务边界统一记录；这里只记录「参数被拒」 这类失败分支，它们是低频的调用方错误，必须留下证据。
 */
public final class StockPosition {

    private static final Logger log = LoggerFactory.getLogger(StockPosition.class);

    private final Sku sku;
    private final Warehouse warehouse;
    private final long onHand;
    private final long allocated;

    private StockPosition(Sku sku, Warehouse warehouse, long onHand, long allocated) {
        this.sku = sku;
        this.warehouse = warehouse;
        this.onHand = onHand;
        this.allocated = allocated;
    }

    /** 零库存水位。 */
    public static StockPosition of(Sku sku, Warehouse warehouse) {
        return new StockPosition(sku, warehouse, 0L, 0L);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Sku sku() {
        return sku;
    }

    public Warehouse warehouse() {
        return warehouse;
    }

    public long onHand() {
        return onHand;
    }

    public long allocated() {
        return allocated;
    }

    /** 可用库存 = 在库 - 已预占。 */
    public long available() {
        return onHand - allocated;
    }

    /** 入库：在库增加 qty，返回新副本。 */
    public StockPosition receive(long qty) {
        requireNonNegative(qty, "入库数量");
        return new StockPosition(sku, warehouse, onHand + qty, allocated);
    }

    /** 预占 qty：已预占增加；若超过在库则抛 OversellException。 */
    public StockPosition allocate(long qty) {
        requireNonNegative(qty, "预占数量");
        if (allocated + qty > onHand) {
            throw new OversellException(
                    "预占超出可用库存：sku=" + sku + " 可用=" + available() + " 欲预占=" + qty);
        }
        return new StockPosition(sku, warehouse, onHand, allocated + qty);
    }

    /** 释放 qty 预占：已预占减少，返回新副本。 */
    public StockPosition release(long qty) {
        requireNonNegative(qty, "释放数量");
        if (qty > allocated) {
            // 超量释放会把库存水位算成负数/虚高，是必须可见的数据一致性错误。
            log.error("释放预占被拒：欲释放={} 超过已预占={} sku={} warehouse={}", qty, allocated, sku, warehouse);
            throw new IllegalArgumentException("释放数量超过已预占：欲释放=" + qty + " 已预占=" + allocated);
        }
        return new StockPosition(sku, warehouse, onHand, allocated - qty);
    }

    /**
     * 确认扣减 qty：在库与已预占同时减少，返回新副本（用于预占确认）。
     *
     * <p><b>为什么判定基准是「已预占 allocated」而不是「可用 available」</b>：confirm 动的是 <em>已经占住</em>的那部分货，扣完之后 {@code
     * onHand} 与 {@code allocated} 同减，{@code available} 反而保持不变。用 {@code available} 当基准会得出荒谬结论——在库
     * 100、已预占 80 时，确认那笔 80 件的预占会因为「可用只有 20」而被拒，而它明明就是合法的：那 80 件本来就是它自己占的。
     * 真正的约束只有一条：不能确认超过当初预占的数量（{@code qty <= allocated}），因为 {@code allocated <= onHand}
     * 由构造器保证，{@code qty <= onHand} 自动成立。
     */
    public StockPosition deduct(long qty) {
        requireNonNegative(qty, "扣减数量");
        if (qty > allocated) {
            throw new OversellException(
                    "确认扣减超出已预占：sku=" + sku + " 已预占=" + allocated + " 欲扣减=" + qty);
        }
        return new StockPosition(sku, warehouse, onHand - qty, allocated - qty);
    }

    /** 直接消耗 qty：仅减少在库（不与预占挂钩），返回新副本。 */
    public StockPosition consume(long qty) {
        requireNonNegative(qty, "扣减数量");
        if (qty > onHand) {
            throw new OversellException("扣减超出在库：sku=" + sku + " 在库=" + onHand + " 欲扣减=" + qty);
        }
        return new StockPosition(sku, warehouse, onHand - qty, allocated);
    }

    private void requireNonNegative(long qty, String what) {
        if (qty < 0) {
            log.warn("库存操作参数被拒：{}不能为负 qty={} sku={} warehouse={}", what, qty, sku, warehouse);
            throw new IllegalArgumentException(what + "不能为负：" + qty);
        }
    }

    /** 库存水位构造器。 */
    public static final class Builder {
        private Sku sku;
        private Warehouse warehouse;
        private long onHand;
        private long allocated;

        public Builder sku(Sku v) {
            this.sku = v;
            return this;
        }

        public Builder warehouse(Warehouse v) {
            this.warehouse = v;
            return this;
        }

        public Builder onHand(long v) {
            this.onHand = v;
            return this;
        }

        public Builder allocated(long v) {
            this.allocated = v;
            return this;
        }

        public StockPosition build() {
            // build() 是外部还原水位的唯一入口，非法水位（负数、预占超过在库）会直接导致超卖，
            // 因此每个拒绝分支都要留痕。
            if (sku == null) {
                log.warn("构造库存水位被拒：sku 必填");
                throw new IllegalArgumentException("sku 必填");
            }
            if (warehouse == null) {
                log.warn("构造库存水位被拒：warehouse 必填 sku={}", sku);
                throw new IllegalArgumentException("warehouse 必填");
            }
            if (onHand < 0) {
                log.warn("构造库存水位被拒：onHand 不能为负 sku={} onHand={}", sku, onHand);
                throw new IllegalArgumentException("onHand 不能为负");
            }
            if (allocated < 0) {
                log.warn("构造库存水位被拒：allocated 不能为负 sku={} allocated={}", sku, allocated);
                throw new IllegalArgumentException("allocated 不能为负");
            }
            if (allocated > onHand) {
                log.warn(
                        "构造库存水位被拒：allocated 超过 onHand sku={} onHand={} allocated={}",
                        sku,
                        onHand,
                        allocated);
                throw new IllegalArgumentException("allocated 不能超过 onHand");
            }
            return new StockPosition(sku, warehouse, onHand, allocated);
        }
    }
}
