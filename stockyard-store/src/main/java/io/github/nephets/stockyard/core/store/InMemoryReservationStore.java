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

import io.github.nephets.stockyard.core.reservation.Reservation;
import io.github.nephets.stockyard.core.support.EvictionTracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于内存的预占存储，仅适合单进程演示与测试。
 *
 * <h2>为什么长生命周期集合必须有界</h2>
 *
 * <p>预占单的数量与「来过的请求」成正比，而请求是无限的。一个无界的 {@code Map} 在长时间运行的进程里 就是内存泄漏：它的增长没有上限，且因为一直被强引用而永远不会成为 GC
 * 的回收目标。库存服务通常 是「启动后几个月不重启」的长驻进程，无界集合是最典型的 OOM 来源。因此这里从一开始就设容量上限，
 * 宁可丢数据也不让进程被自己撑死——<b>「可控的降级」永远优于「不可控的崩溃」</b>。
 *
 * <h2>为什么用 LRU 而不是 FIFO</h2>
 *
 * <p>预占单的访问有明显的局部性：刚创建的一定会被 confirm/cancel，被查询过的一定还会被再查。 LRU（{@code LinkedHashMap} 的 {@code
 * accessOrder=true}）淘汰「最久没被碰过的」，正好把仍在 流程中的热数据留下；FIFO 淘汰的是「最早进来的」，在长尾订单（下单后几小时才支付）场景里会
 * 精准地把还在等待支付的单子丢掉。注意 {@code get} 也算访问，会把它挪到队尾——这正是我们想要的。
 *
 * <h2>为什么淘汰必须留痕</h2>
 *
 * <p>淘汰是<b>静默的数据丢失</b>：预占单被丢掉后表现为「这个单子从来没存在过」，调用方拿到的是 {@code
 * ReservationNotFoundException}，而真正的原因是「容量配小了」。不留痕的话，排查只能靠猜。 因此这里既打 WARN（首次淘汰与之后每 {@value
 * EvictionTracker#REPORT_INTERVAL} 条各一条，避免 热路径刷爆日志），也通过 {@link #evictedCount()}
 * 暴露一个可直接读取的降级标记。运维只要看这个 计数是否为 0，就知道容量是否够用。
 *
 * <p><b>为什么是聚合打点而不是逐条</b>：稳态下容量打满意味着「每写入一条就淘汰一条」，逐条 WARN 等于把热路径的每一次写入都打一条日志，既淹没有效日志又拖慢写入。
 */
public class InMemoryReservationStore implements ReservationStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryReservationStore.class);

    /** 默认容量上限：超过后按 LRU（最久未访问）淘汰最旧预占单。 */
    public static final long DEFAULT_CAPACITY = 1_000_000L;

    private final Map<String, Reservation> map;
    private final EvictionTracker evictions = new EvictionTracker("InMemoryReservationStore");

    public InMemoryReservationStore() {
        this(DEFAULT_CAPACITY);
    }

    /** 指定容量上限（<=0 时退化为 1），超过后淘汰最旧的预占单。 */
    public InMemoryReservationStore(long capacity) {
        final long cap = Math.max(1L, capacity);
        // 容量上限决定了「多久开始静默淘汰」，必须在创建时可见，否则排障时无从判断配置是否生效。
        log.debug("创建 InMemoryReservationStore，容量上限={}", cap);
        this.map =
                Collections.synchronizedMap(
                        new LinkedHashMap<String, Reservation>(16, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(
                                    Map.Entry<String, Reservation> eldest) {
                                // 淘汰是静默降级：必须记账并告警，否则旧预占单消失时无人知晓。
                                boolean evict = size() > cap;
                                if (evict) {
                                    evictions.onEvict();
                                }
                                return evict;
                            }
                        });
    }

    /**
     * 累计淘汰的预占单条数（>{@code 0} 说明容量曾经打满，已有数据被静默丢弃）。
     *
     * <p>为什么暴露它：光靠日志只能事后翻，运维与回归测试需要一个可直接读取的降级标记来判断 「当前容量是否够用」。
     */
    public long evictedCount() {
        return evictions.count();
    }

    @Override
    public Reservation save(Reservation reservation) {
        map.put(reservation.id(), reservation);
        return reservation;
    }

    @Override
    public Optional<Reservation> find(String id) {
        return Optional.ofNullable(map.get(id));
    }

    @Override
    public List<Reservation> findAll() {
        synchronized (map) {
            return new ArrayList<>(map.values());
        }
    }
}
