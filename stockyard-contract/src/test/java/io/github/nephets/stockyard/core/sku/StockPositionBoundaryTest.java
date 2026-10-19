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

import io.github.nephets.stockyard.core.OversellException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存水位四则运算的边界。
 *
 * <p>为什么每个边界都要单独钉住：库存算错的表现不是报错而是「账实不符」，而这种错误往往在几周后的 对账才被发现。边界断言越早失败越好。
 */
class StockPositionBoundaryTest {

    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private StockPosition pos(long onHand, long allocated) {
        return StockPosition.builder()
                .sku(sku)
                .warehouse(wh)
                .onHand(onHand)
                .allocated(allocated)
                .build();
    }

    @Test
    void available_is_on_hand_minus_allocated() {
        assertThat(pos(10, 3).available()).isEqualTo(7);
    }

    @Test
    void receive_zero_is_allowed_and_keeps_on_hand() {
        StockPosition after = pos(10, 3).receive(0);
        assertThat(after.onHand()).isEqualTo(10);
        assertThat(after.allocated()).isEqualTo(3);
        assertThat(after.available()).isEqualTo(7);
    }

    @Test
    void receive_negative_is_rejected() {
        assertThatThrownBy(() -> pos(10, 0).receive(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("入库数量");
    }

    @Test
    void allocate_zero_is_allowed() {
        StockPosition after = pos(10, 3).allocate(0);
        assertThat(after.allocated()).isEqualTo(3);
        assertThat(after.available()).isEqualTo(7);
    }

    @Test
    void allocate_negative_is_rejected() {
        assertThatThrownBy(() -> pos(10, 0).allocate(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预占数量");
    }

    @Test
    void allocate_exactly_the_available_amount_is_allowed() {
        // 边界：可用 7，预占 7 恰好成立；预占 8 必须失败。这条边界错了就是超卖。
        StockPosition after = pos(10, 3).allocate(7);
        assertThat(after.allocated()).isEqualTo(10);
        assertThat(after.available()).isZero();
    }

    @Test
    void allocate_one_more_than_available_throws_oversell() {
        assertThatThrownBy(() -> pos(10, 3).allocate(8)).isInstanceOf(OversellException.class);
    }

    @Test
    void allocate_is_checked_against_on_hand_not_against_available_minus_pending_releases() {
        // 已预占 3 之后再预占 4（3+4=7 <= 10）可行，说明判定基准是 onHand 而不是 available。
        assertThat(pos(10, 3).allocate(4).allocated()).isEqualTo(7);
    }

    @Test
    void release_zero_is_allowed() {
        assertThat(pos(10, 3).release(0).allocated()).isEqualTo(3);
    }

    @Test
    void release_negative_is_rejected() {
        assertThatThrownBy(() -> pos(10, 3).release(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("释放数量");
    }

    @Test
    void release_exactly_the_allocated_amount_zeroes_allocation() {
        StockPosition after = pos(10, 3).release(3);
        assertThat(after.allocated()).isZero();
        assertThat(after.available()).isEqualTo(10);
    }

    @Test
    void release_more_than_allocated_is_rejected_as_a_data_consistency_error() {
        // 超量释放会把水位算成虚高（相当于凭空造货），必须拒绝而不是静默接受。
        assertThatThrownBy(() -> pos(10, 3).release(4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("释放数量超过已预占");
    }

    @Test
    void deduct_moves_the_allocated_part_out_of_the_warehouse() {
        // confirm 路径：在库与已预占同时减少。这是「预占转实扣」的唯一正确算法——只减 onHand
        // 会让已预占量虚高（库存越卖越多），只减 allocated 会让在库虚高（货没出库却少了）。
        StockPosition after = pos(10, 3).deduct(3);
        assertThat(after.onHand()).isEqualTo(7);
        assertThat(after.allocated()).isZero();
        assertThat(after.available()).isEqualTo(7);
    }

    @Test
    void deduct_more_than_allocated_is_rejected() {
        // confirm 动的只能是当初占住的那部分货：扣掉不属于自己的预占量等于凭空出库。
        assertThatThrownBy(() -> pos(10, 3).deduct(4))
                .isInstanceOf(OversellException.class)
                .hasMessageContaining("已预占");
    }

    @Test
    void confirming_a_reservation_that_holds_most_of_the_stock_is_allowed() {
        // 这是「可用量」当判据会误杀的场景：在库 100、已预占 80，确认那笔 80 件的预占必须成功。
        // 若按 available(=20) 判定，80 > 20 会被拒——而它扣的正是自己当初占住的 80 件。
        StockPosition after = pos(100, 80).deduct(80);
        assertThat(after.onHand()).isEqualTo(20);
        assertThat(after.allocated()).isZero();
        assertThat(after.available()).isEqualTo(20);
    }

    @Test
    void confirm_leaves_the_available_amount_unchanged() {
        // 预占转实扣不改变可用量：货从「在库+已占」搬走，剩下的可卖数量还是那么多。
        StockPosition before = pos(10, 3);
        assertThat(before.deduct(3).available()).isEqualTo(before.available());
    }

    @Test
    void deduct_more_than_available_throws_oversell() {
        // confirm 路径上的扣减基准是 available（onHand - allocated），不是 onHand。
        assertThatThrownBy(() -> pos(10, 3).deduct(8)).isInstanceOf(OversellException.class);
    }

    @Test
    void consume_more_than_on_hand_throws_oversell() {
        // 直接扣减不与预占挂钩，判定基准是 onHand（不是 available）。引擎的 deduct 在调用 consume
        // 之前已用 available() 前置检查兜住，所以这条只在直接使用值对象时才会触发。
        assertThatThrownBy(() -> pos(10, 3).consume(11)).isInstanceOf(OversellException.class);
    }

    @Test
    void consume_up_to_unallocated_on_hand_is_allowed() {
        // 在库 10、已预占 3：直接消耗最多只能动到 7，否则会吃掉别人的预占。
        StockPosition after = pos(10, 3).consume(7);
        assertThat(after.onHand()).isEqualTo(3);
        assertThat(after.allocated()).isEqualTo(3);
        assertThat(after.available()).isZero();
    }

    @Test
    void very_large_quantity_does_not_silently_pass_the_availability_check() {
        // Long.MAX_VALUE 级别的请求量不能因为溢出而绕过「不足就拒绝」的判定。
        assertThatThrownBy(() -> pos(10, 0).allocate(Long.MAX_VALUE))
                .isInstanceOf(OversellException.class);
        assertThatThrownBy(() -> pos(10, 0).consume(Long.MAX_VALUE))
                .isInstanceOf(OversellException.class);
        assertThatThrownBy(() -> pos(10, 5).deduct(Long.MAX_VALUE))
                .isInstanceOf(OversellException.class);
    }
}
