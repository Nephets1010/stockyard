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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** 仓库标识。 */
public final class Warehouse {

    private static final Logger log = LoggerFactory.getLogger(Warehouse.class);

    private final String code;

    private Warehouse(String code) {
        this.code = code;
    }

    /** 用编码构造仓库；编码为空或仅空白时抛异常（失败分支留痕，成功路径不打日志）。 */
    public static Warehouse of(String code) {
        if (code == null || code.isBlank()) {
            log.warn("构造 Warehouse 被拒：code 不能为空");
            throw new IllegalArgumentException("warehouse code 不能为空");
        }
        return new Warehouse(code);
    }

    public String code() {
        return code;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Warehouse)) {
            return false;
        }
        return code.equals(((Warehouse) o).code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return "Warehouse{" + code + "}";
    }
}
