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

import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.StockPosition;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.support.EvictionTracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于内存的库存水位存储，仅适合单进程演示与测试。
 *
 * <p><b>为什么水位存储尤其不能有界失效而不留痕</b>：库存水位被淘汰后，这个 (sku,warehouse) 的库存会
 * 「凭空归零」——不是真的卖完了，而是记录没了。若再叠加自动补货，会出现「明明有货却一直补、一补就 又归零」的诡异循环。因此容量上限与 {@link #evictedCount()}
 * 必须同时可见：前者告诉你什么时候会 开始丢，后者告诉你已经丢了多少。
 *
 * <p><b>为什么用 LRU</b>：库存水位的访问局部性极强——热销 SKU 每秒被读写上百次，滞销 SKU 几周才动 一次。LRU 恰好把滞销的淘汰掉，把热销的留在内存里。
 *
 * <p>{@code getOrCreate} 里的 {@code get} 也算一次访问：即使没有写入，只要被读过就会挪到 LRU 队尾， 这保证「还在被查询的冷门 SKU」不会被误淘汰。
 */
public class InMemoryStockPositionStore implements StockPositionStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStockPositionStore.class);

    /** 默认容量上限：超过后按 LRU（最久未访问）淘汰最旧水位。 */
    public static final long DEFAULT_CAPACITY = 1_000_000L;

    private final Map<String, StockPosition> map;
    private final EvictionTracker evictions = new EvictionTracker("InMemoryStockPositionStore");

    public InMemoryStockPositionStore() {
        this(DEFAULT_CAPACITY);
    }

    /** 指定容量上限（<=0 时退化为 1），超过后淘汰最旧的水位。 */
    public InMemoryStockPositionStore(long capacity) {
        final long cap = Math.max(1L, capacity);
        // 容量上限决定了「多久开始静默淘汰」，必须在创建时可见，否则排障时无从判断配置是否生效。
        log.debug("创建 InMemoryStockPositionStore，容量上限={}", cap);
        this.map =
                Collections.synchronizedMap(
                        new LinkedHashMap<String, StockPosition>(16, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(
                                    Map.Entry<String, StockPosition> eldest) {
                                // 水位被淘汰会让该 (sku,warehouse) 的库存「凭空归零」，必须可见。
                                boolean evict = size() > cap;
                                if (evict) {
                                    evictions.onEvict();
                                }
                                return evict;
                            }
                        });
    }

    /**
     * 累计淘汰的库存水位条数（>{@code 0} 说明容量曾经打满，已有水位被静默丢弃、库存会表现为归零）。
     *
     * <p>为什么暴露它：光靠日志只能事后翻，运维与回归测试需要一个可直接读取的降级标记来判断 「当前容量是否够用」。
     */
    public long evictedCount() {
        return evictions.count();
    }

    private static String key(Sku sku, Warehouse warehouse) {
        return sku.code() + "|" + warehouse.code();
    }

    @Override
    public Optional<StockPosition> find(Sku sku, Warehouse warehouse) {
        return Optional.ofNullable(map.get(key(sku, warehouse)));
    }

    @Override
    public StockPosition getOrCreate(Sku sku, Warehouse warehouse) {
        String k = key(sku, warehouse);
        StockPosition p = map.get(k);
        if (p == null) {
            p = StockPosition.of(sku, warehouse);
            map.put(k, p);
        }
        return p;
    }

    @Override
    public void save(StockPosition position) {
        map.put(key(position.sku(), position.warehouse()), position);
    }
}
