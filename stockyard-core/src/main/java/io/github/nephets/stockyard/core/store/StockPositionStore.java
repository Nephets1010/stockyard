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

import java.util.Optional;

/** 库存水位存储接口。 */
public interface StockPositionStore {

    /** 按 sku + 仓库查找当前水位。 */
    Optional<StockPosition> find(Sku sku, Warehouse warehouse);

    /** 查找，不存在则创建零库存水位。 */
    StockPosition getOrCreate(Sku sku, Warehouse warehouse);

    /** 保存水位（覆盖）。 */
    void save(StockPosition position);
}
