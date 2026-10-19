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

import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分片函数的性质：确定性、有界、分布均匀。
 *
 * <p>为什么这三条性质每条都要单独钉住：
 *
 * <ul>
 *   <li><b>确定性</b>——同一个 (sku,warehouse) 每次必须落到同一把锁，否则「读—改—写」会被两把不同的 锁保护，等于没有锁，直接超卖；
 *   <li><b>有界</b>——索引越界会变成 {@code ArrayIndexOutOfBoundsException}，把一次库存操作变成 500；负哈希必须靠 {@code
 *       floorMod} 兜住；
 *   <li><b>分布均匀</b>——分布退化会把大量 key 挤到少数槽上，防超卖仍然成立但吞吐塌掉。
 * </ul>
 */
class StockyardEngineLockStripeDistributionTest {

    private static final int STRIPES = StockyardEngine.POSITION_LOCK_STRIPES;

    @Test
    void index_is_always_within_the_stripe_array() {
        for (int i = 0; i < 10_000; i++) {
            int idx = StockyardEngine.positionLockIndex(Sku.of("S" + i), Warehouse.of("W" + i));
            assertThat(idx).isBetween(0, STRIPES - 1);
        }
    }

    @Test
    void index_is_never_negative_even_for_negative_hashes() {
        // Objects.hash 可能返回负数；若用 `hash % 64` 会得到负索引直接越界，floorMod 是必须的。
        for (int i = 0; i < 10_000; i++) {
            assertThat(StockyardEngine.positionLockIndex(Sku.of("S" + i), Warehouse.of("W")))
                    .isNotNegative();
        }
    }

    @Test
    void the_same_key_always_maps_to_the_same_slot() {
        // 这是防超卖的前提：锁的映射必须稳定，否则两次操作会被两把不同的锁保护。
        for (int i = 0; i < 200; i++) {
            Sku sku = Sku.of("S" + i);
            Warehouse wh = Warehouse.of("W" + i);
            int first = StockyardEngine.positionLockIndex(sku, wh);
            for (int k = 0; k < 10; k++) {
                assertThat(StockyardEngine.positionLockIndex(sku, wh)).isEqualTo(first);
            }
        }
    }

    @Test
    void the_same_sku_in_different_warehouses_spreads_across_slots() {
        // 为什么哈希必须同时吃 sku 与 warehouse：同 SKU 多仓是常态，只按 SKU 分片会把它们全挤在一把锁上，
        // 多仓并行发货的吞吐直接退化成串行。
        Set<Integer> slots = new HashSet<>();
        Sku sku = Sku.of("SKU-1");
        for (int i = 0; i < 32; i++) {
            slots.add(StockyardEngine.positionLockIndex(sku, Warehouse.of("WH-" + i)));
        }
        assertThat(slots).hasSizeGreaterThan(8);
    }

    @Test
    void stripe_count_is_a_power_of_two_so_modulo_is_a_bit_mask() {
        // 64 = 2^6：floorMod(hash, 64) 可被 JIT 优化成 hash & 63，取模不走除法。
        assertThat(STRIPES).isEqualTo(64);
        assertThat(Integer.bitCount(STRIPES)).isEqualTo(1);
    }

    @Test
    void a_realistic_key_space_distributes_over_most_slots() {
        Set<Integer> slots = new HashSet<>();
        for (int s = 0; s < 500; s++) {
            for (int w = 0; w < 4; w++) {
                slots.add(
                        StockyardEngine.positionLockIndex(
                                Sku.of("SKU-" + s), Warehouse.of("WH-" + w)));
            }
        }
        // 2000 个 key 落到 64 个槽：至少应覆盖一半以上槽位，否则哈希退化。
        assertThat(slots).hasSizeGreaterThanOrEqualTo(STRIPES / 2);
    }
}
