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

/** 库存单位（商品）标识。 */
public final class Sku {

    private static final Logger log = LoggerFactory.getLogger(Sku.class);

    private final String code;

    private Sku(String code) {
        this.code = code;
    }

    /**
     * 用编码构造 SKU；编码为空或仅空白时抛异常。
     *
     * <p>为什么在值对象里打日志：{@code of()} 是对外边界，空编码通常来自未校验的外部输入（HTTP 参数、消息体），留一条 warn
     * 才能在事后定位是哪个入口漏了校验。成功路径不打日志——构造 SKU 太频繁。
     */
    public static Sku of(String code) {
        if (code == null || code.isBlank()) {
            log.warn("构造 Sku 被拒：code 不能为空");
            throw new IllegalArgumentException("sku code 不能为空");
        }
        return new Sku(code);
    }

    public String code() {
        return code;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Sku)) {
            return false;
        }
        return code.equals(((Sku) o).code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return "Sku{" + code + "}";
    }
}
