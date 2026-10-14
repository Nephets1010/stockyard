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

class StockPositionTest {

    private final Sku sku = Sku.of("S1");
    private final Warehouse wh = Warehouse.of("W1");

    @Test
    void of_is_zero() {
        StockPosition p = StockPosition.of(sku, wh);
        assertThat(p.onHand()).isZero();
        assertThat(p.allocated()).isZero();
        assertThat(p.available()).isZero();
    }

    @Test
    void receive_increases_onhand() {
        StockPosition p = StockPosition.of(sku, wh).receive(10);
        assertThat(p.onHand()).isEqualTo(10);
        assertThat(p.available()).isEqualTo(10);
    }

    @Test
    void allocate_then_release() {
        StockPosition p = StockPosition.of(sku, wh).receive(10).allocate(4);
        assertThat(p.allocated()).isEqualTo(4);
        assertThat(p.available()).isEqualTo(6);
        StockPosition r = p.release(4);
        assertThat(r.allocated()).isZero();
        assertThat(r.available()).isEqualTo(10);
    }

    @Test
    void allocate_over_available_throws() {
        StockPosition p = StockPosition.of(sku, wh).receive(5);
        assertThatThrownBy(() -> p.allocate(6)).isInstanceOf(OversellException.class);
    }

    @Test
    void deduct_reduces_both() {
        StockPosition p = StockPosition.of(sku, wh).receive(10).allocate(3).deduct(3);
        assertThat(p.onHand()).isEqualTo(7);
        assertThat(p.allocated()).isZero();
    }

    @Test
    void deduct_over_available_throws() {
        StockPosition p = StockPosition.of(sku, wh).receive(2);
        assertThatThrownBy(() -> p.deduct(3)).isInstanceOf(OversellException.class);
    }

    @Test
    void consume_reduces_onhand_only() {
        StockPosition p = StockPosition.of(sku, wh).receive(10).allocate(4).consume(3);
        assertThat(p.onHand()).isEqualTo(7);
        assertThat(p.allocated()).isEqualTo(4);
    }

    @Test
    void consume_over_onhand_throws() {
        StockPosition p = StockPosition.of(sku, wh).receive(2);
        assertThatThrownBy(() -> p.consume(3)).isInstanceOf(OversellException.class);
    }

    @Test
    void immutable_returns_new() {
        StockPosition p = StockPosition.of(sku, wh).receive(5);
        p.receive(5);
        assertThat(p.onHand()).isEqualTo(5);
    }

    @Test
    void builder_validates() {
        assertThatThrownBy(() -> StockPosition.builder().sku(sku).warehouse(wh).onHand(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                StockPosition.builder()
                                        .sku(sku)
                                        .warehouse(wh)
                                        .onHand(5)
                                        .allocated(6)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockPosition.builder().warehouse(wh).onHand(1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
