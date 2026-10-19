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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** 租户标识。仅持有不可变的字符串编号，用于多租户库存隔离。 */
public final class StockyardTenant {

    private static final Logger log = LoggerFactory.getLogger(StockyardTenant.class);

    private final String id;

    private StockyardTenant(String id) {
        this.id = id;
    }

    /**
     * 用编号构造租户；编号为空或仅空白时抛异常。
     *
     * <p>租户号缺失意味着库存可能串租户，是必须可见的安全信号，因此失败分支打 warn；成功路径不打日志。
     */
    public static StockyardTenant of(String id) {
        if (id == null || id.isBlank()) {
            log.warn("构造 StockyardTenant 被拒：tenant id 不能为空");
            throw new IllegalArgumentException("tenant id 不能为空");
        }
        return new StockyardTenant(id);
    }

    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StockyardTenant)) {
            return false;
        }
        return id.equals(((StockyardTenant) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "StockyardTenant{" + id + "}";
    }
}
