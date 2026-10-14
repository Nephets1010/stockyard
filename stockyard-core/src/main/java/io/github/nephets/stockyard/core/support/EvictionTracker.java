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
package io.github.nephets.stockyard.core.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内存存储「淘汰」事件的聚合计数器。
 *
 * <p>为什么需要它：三个内存存储都用 {@code LinkedHashMap.removeEldestEntry} 做 LRU 淘汰，而这个回调是
 * 静默的——容量打满之后每写入一条就无声丢弃一条最旧数据（库存水位、预占单或幂等扣减记录）。调用方 完全无感，表现为「预占单莫名其妙查不到」「幂等去重失效」这类难排查的问题。因此把淘汰上升为可观测
 * 事件：既打日志，也提供 {@link #count()} 供运维/测试读取。
 *
 * <p>为什么是聚合打点而不是逐条：稳态下容量打满意味着每次写入都会淘汰一条，逐条 warn 等于把热路径 的每一次写入都打一条日志，既淹没有效日志又拖慢写入。这里只在首次淘汰时立刻告警，之后每
 * {@value #REPORT_INTERVAL} 条汇总一条，兼顾可观测与性能。
 *
 * <p>线程安全约定：{@link #onEvict()} 由调用方在持有存储自身锁的情况下调用（三个内存存储都用了 {@code Collections.synchronizedMap}
 * 包裹，{@code removeEldestEntry} 一定在该锁内执行）； {@link #count()} 读取 volatile 字段，可被任意线程调用。
 */
public final class EvictionTracker {

    private static final Logger log = LoggerFactory.getLogger(EvictionTracker.class);

    /** 汇总打点间隔：首次淘汰之外，每累计这么多条再打一条日志。 */
    public static final long REPORT_INTERVAL = 10_000L;

    private final String storeName;
    private volatile long count;

    /**
     * @param storeName 存储名，用于日志定位（例如 {@code InMemoryReservationStore}）
     */
    public EvictionTracker(String storeName) {
        this.storeName = storeName;
    }

    /** 记录一次淘汰。必须在调用方持有存储锁时调用。 */
    public void onEvict() {
        long n = count + 1L;
        count = n;
        if (n == 1L || n % REPORT_INTERVAL == 0L) {
            log.warn("{} 容量已满并发生 LRU 淘汰，累计淘汰 {} 条；请调大容量或改用持久化存储，否则旧数据会被静默丢弃", storeName, n);
        }
    }

    /** 自该存储创建以来累计淘汰的条目数；0 表示从未淘汰。 */
    public long count() {
        return count;
    }

    @Override
    public String toString() {
        return "EvictionTracker{" + storeName + ",count=" + count + "}";
    }
}
