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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 仓库值对象的等值语义与输入校验。
 *
 * <p>为什么仓库必须参与分片锁的哈希：同一个 SKU 分布在北京仓和上海仓是常态。若锁只按 SKU 分片， 「同 SKU 不同仓」的全部操作会挤在同一把锁上，多仓并行发货的吞吐直接退化成串行。
 */
class WarehouseValueObjectTest {

    @Test
    void same_code_yields_equal_instances_with_equal_hash() {
        assertThat(Warehouse.of("WH-1"))
                .isEqualTo(Warehouse.of("WH-1"))
                .hasSameHashCodeAs(Warehouse.of("WH-1"));
    }

    @Test
    void different_codes_are_not_equal() {
        assertThat(Warehouse.of("WH-1")).isNotEqualTo(Warehouse.of("WH-2"));
    }

    @Test
    void equality_is_type_safe() {
        assertThat(Warehouse.of("WH-1")).isNotEqualTo("WH-1");
    }

    @Test
    void distinct_warehouses_are_kept_separately_in_a_hash_set() {
        Set<Warehouse> set = new LinkedHashSet<>();
        for (int i = 0; i < 50; i++) {
            set.add(Warehouse.of("WH-" + i));
        }
        assertThat(set).hasSize(50);
    }

    @Test
    void blank_and_null_codes_are_rejected() {
        assertThatThrownBy(() -> Warehouse.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warehouse code 不能为空");
        assertThatThrownBy(() -> Warehouse.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Warehouse.of("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_warehouse_is_not_a_sku_even_with_the_same_code() {
        // 两者都是单字段值对象但语义不同；Java 的 instanceof 检查已经阻止了跨类型相等。
        assertThat(Warehouse.of("X")).isNotEqualTo(Sku.of("X"));
    }

    @Test
    void toString_contains_the_code_for_log_correlation() {
        assertThat(Warehouse.of("WH-1").toString()).isEqualTo("Warehouse{WH-1}");
    }
}
