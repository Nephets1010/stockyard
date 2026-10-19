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

import io.github.nephets.stockyard.core.support.EvictionTracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于内存的扣减记录存储，仅适合单进程演示与测试。
 *
 * <p><b>为什么幂等记录的淘汰是最危险的一种静默降级</b>：预占单丢了表现为「查不到」，库存水位丢了 表现为「归零」，都还能从现象反推；而幂等记录被淘汰后，同一个 requestId
 * 的下一次重试会重新判定为 「首次提交」，<b>于是同一笔业务被扣两次</b>——现象是资损，原因却藏在容量配置里。因此这里的淘汰 计数（{@link
 * #evictedCount()}）必须被纳入监控：只要它不为 0，幂等保证就已经失效，需要立刻扩容 或换持久化存储。
 *
 * <p><b>为什么用 LRU 而不是 FIFO</b>：重试有长尾——MQ 延迟重投可能让一条几小时前写入的记录突然被 再次查询。FIFO 只看「什么时候进来的」，会把它淘汰掉；LRU
 * 看「最后一次被访问是什么时候」， 只要这条记录刚被重试命中过就会被挪回队尾保留下来。也就是说 LRU 能把「老但仍然活跃」的幂等键 保住，这正是防重复扣减最需要的那一条。
 *
 * <p><b>为什么默认容量设成 {@value #DEFAULT_CAPACITY}</b>：单进程演示/测试场景下一个 requestId 的 记录约 200 B，100 万条约 200
 * MB，是演示可以接受的上限；生产请换成带 TTL 清理的持久化存储 （Redis / DB 唯一索引），而不是把内存容量往上调。
 */
public class InMemoryDeductionStore implements DeductionStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDeductionStore.class);

    /** 默认容量上限：超过后按 LRU（最久未访问）淘汰最旧记录。 */
    public static final long DEFAULT_CAPACITY = 1_000_000L;

    private final Map<String, DeductionRecord> map;
    private final EvictionTracker evictions = new EvictionTracker("InMemoryDeductionStore");

    public InMemoryDeductionStore() {
        this(DEFAULT_CAPACITY);
    }

    /** 指定容量上限（<=0 时退化为 1），超过后淘汰最旧的记录。 */
    public InMemoryDeductionStore(long capacity) {
        final long cap = Math.max(1L, capacity);
        // 容量上限决定了「多久开始静默淘汰」，必须在创建时可见，否则排障时无从判断配置是否生效。
        log.debug("创建 InMemoryDeductionStore，容量上限={}", cap);
        this.map =
                Collections.synchronizedMap(
                        new LinkedHashMap<String, DeductionRecord>(16, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(
                                    Map.Entry<String, DeductionRecord> eldest) {
                                // 记录被淘汰会让幂等去重失效（同一 requestId 可能被重复扣减），必须可见。
                                boolean evict = size() > cap;
                                if (evict) {
                                    evictions.onEvict();
                                }
                                return evict;
                            }
                        });
    }

    /**
     * 累计淘汰的扣减记录条数（>{@code 0} 说明容量曾经打满，幂等去重可能已失效）。
     *
     * <p>为什么暴露它：光靠日志只能事后翻，运维与回归测试需要一个可直接读取的降级标记来判断 「当前容量是否够用」。
     */
    public long evictedCount() {
        return evictions.count();
    }

    @Override
    public Optional<DeductionRecord> find(String requestId) {
        return Optional.ofNullable(map.get(requestId));
    }

    @Override
    public void save(DeductionRecord record) {
        map.put(record.requestId(), record);
    }
}
