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

/** 基于内存的预占存储，仅适合单进程演示与测试。 */
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
