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

class WarehouseTest {

    @Test
    void of_creates() {
        assertThat(Warehouse.of("W1").code()).isEqualTo("W1");
    }

    @Test
    void blank_throws() {
        assertThatThrownBy(() -> Warehouse.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Warehouse.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equals_by_code() {
        assertThat(Warehouse.of("a")).isEqualTo(Warehouse.of("a"));
        assertThat(Warehouse.of("a")).isNotEqualTo(Warehouse.of("b"));
    }
}
