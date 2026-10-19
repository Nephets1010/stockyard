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
 * SKU 值对象的等值语义与输入校验。
 *
 * <p>为什么值对象必须严格校验：SKU 是分片锁哈希与存储 key 的输入，一个空/空白的 code 会让一批完全不同 的商品落到同一个 key 上——表现为「两个商品的库存互相干扰」。
 */
class SkuValueObjectTest {

    @Test
    void same_code_yields_equal_instances_with_equal_hash() {
        assertThat(Sku.of("SKU-1")).isEqualTo(Sku.of("SKU-1")).hasSameHashCodeAs(Sku.of("SKU-1"));
    }

    @Test
    void different_codes_are_not_equal() {
        assertThat(Sku.of("SKU-1")).isNotEqualTo(Sku.of("SKU-2"));
    }

    @Test
    void equality_is_type_safe() {
        assertThat(Sku.of("SKU-1")).isNotEqualTo("SKU-1");
    }

    @Test
    void distinct_skus_are_kept_separately_in_a_hash_set() {
        // 这条断言保护的是「存储 key 不混淆」：若 hashCode 退化，Set 会出现诡异的去重。
        Set<Sku> set = new LinkedHashSet<>();
        for (int i = 0; i < 50; i++) {
            set.add(Sku.of("SKU-" + i));
        }
        assertThat(set).hasSize(50);
    }

    @Test
    void blank_and_null_codes_are_rejected() {
        assertThatThrownBy(() -> Sku.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku code 不能为空");
        assertThatThrownBy(() -> Sku.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sku.of("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sku.of("\t\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void code_is_preserved_verbatim_without_trimming() {
        // 刻意不做 trim：库存编码里的空格是有意义的字符，静默规范化会让「A B」和「AB」混为一谈。
        assertThat(Sku.of(" SKU ").code()).isEqualTo(" SKU ");
    }

    @Test
    void toString_contains_the_code_for_log_correlation() {
        assertThat(Sku.of("SKU-1").toString()).isEqualTo("Sku{SKU-1}");
    }
}
