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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存水位构造期的非法态拒绝。
 *
 * <p>为什么必须在构造期拒绝而不是等到使用时：水位是从存储里反序列化回来的，一个 {@code allocated > onHand}
 * 的水位意味着「已经卖出去的比仓库里有的还多」，它在后续任何一次运算里 都会算出负数可用量，而错误现场早就丢了。构造期拒绝能把缺陷钉在它产生的那一刻。
 */
class StockPositionBuilderValidationTest {

    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    @Test
    void valid_position_is_built() {
        StockPosition p =
                StockPosition.builder().sku(sku).warehouse(wh).onHand(10).allocated(3).build();
        assertThat(p.onHand()).isEqualTo(10);
        assertThat(p.allocated()).isEqualTo(3);
        assertThat(p.available()).isEqualTo(7);
    }

    @Test
    void zero_position_is_valid() {
        assertThat(StockPosition.builder().sku(sku).warehouse(wh).build().available()).isZero();
    }

    @Test
    void missing_sku_is_rejected() {
        assertThatThrownBy(() -> StockPosition.builder().warehouse(wh).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku 必填");
    }

    @Test
    void missing_warehouse_is_rejected() {
        assertThatThrownBy(() -> StockPosition.builder().sku(sku).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warehouse 必填");
    }

    @Test
    void negative_on_hand_is_rejected() {
        assertThatThrownBy(() -> StockPosition.builder().sku(sku).warehouse(wh).onHand(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("onHand 不能为负");
    }

    @Test
    void negative_allocated_is_rejected() {
        assertThatThrownBy(
                        () -> StockPosition.builder().sku(sku).warehouse(wh).allocated(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allocated 不能为负");
    }

    @Test
    void allocated_greater_than_on_hand_is_rejected() {
        // 这就是「超卖后的水位」，必须在构造时就被挡住。
        assertThatThrownBy(
                        () ->
                                StockPosition.builder()
                                        .sku(sku)
                                        .warehouse(wh)
                                        .onHand(5)
                                        .allocated(6)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allocated 不能超过 onHand");
    }

    @Test
    void allocated_equal_to_on_hand_is_allowed() {
        // 全部库存都已被预占：可用为 0，是合法状态（卖完了但还没出库）。
        StockPosition p =
                StockPosition.builder().sku(sku).warehouse(wh).onHand(5).allocated(5).build();
        assertThat(p.available()).isZero();
    }
}
