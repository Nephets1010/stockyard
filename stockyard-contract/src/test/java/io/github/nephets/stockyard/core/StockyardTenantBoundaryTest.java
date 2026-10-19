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
package io.github.nephets.stockyard.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户标识的等值语义与校验。
 *
 * <p>为什么租户号缺失必须抛异常而不是退化成「默认租户」：一旦允许空租户，两个不同租户的库存就会 落进同一个 key 里互相覆盖——这是最难排查的一类数据事故，因为它在功能测试里完全看不出来。
 */
class StockyardTenantBoundaryTest {

    @Test
    void same_id_yields_equal_instances_with_equal_hash() {
        assertThat(StockyardTenant.of("t1"))
                .isEqualTo(StockyardTenant.of("t1"))
                .hasSameHashCodeAs(StockyardTenant.of("t1"));
    }

    @Test
    void different_ids_are_not_equal() {
        assertThat(StockyardTenant.of("t1")).isNotEqualTo(StockyardTenant.of("t2"));
    }

    @Test
    void equality_is_type_safe() {
        assertThat(StockyardTenant.of("t1")).isNotEqualTo("t1");
    }

    @Test
    void null_and_blank_ids_are_rejected() {
        assertThatThrownBy(() -> StockyardTenant.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant id 不能为空");
        assertThatThrownBy(() -> StockyardTenant.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockyardTenant.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void id_is_exposed_verbatim() {
        assertThat(StockyardTenant.of("acme").id()).isEqualTo("acme");
    }

    @Test
    void toString_contains_the_id_for_log_correlation() {
        assertThat(StockyardTenant.of("acme").toString()).isEqualTo("StockyardTenant{acme}");
    }
}
