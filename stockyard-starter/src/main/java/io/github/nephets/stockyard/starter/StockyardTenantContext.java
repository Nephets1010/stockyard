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
package io.github.nephets.stockyard.starter;

import io.github.nephets.stockyard.core.StockyardTenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 当前请求的租户上下文（线程局部）。 */
public final class StockyardTenantContext {

    private static final Logger log = LoggerFactory.getLogger(StockyardTenantContext.class);

    private static final ThreadLocal<StockyardTenant> HOLDER = new ThreadLocal<>();

    private StockyardTenantContext() {}

    /**
     * 绑定租户到当前线程。
     *
     * <p>为什么用 debug 而不是 info：绑定/清理每请求一次，属于高频路径；留痕的目的是在排查「租户串号」 时能打开 debug 看到绑定了谁，常态下不应刷日志。
     */
    public static void set(StockyardTenant tenant) {
        HOLDER.set(tenant);
        log.debug("绑定租户上下文 tenant={}", tenant);
    }

    public static StockyardTenant get() {
        return HOLDER.get();
    }

    /**
     * 清理当前线程的租户上下文。
     *
     * <p>必须调用（过滤器已在 finally 中调用）：线程池复用线程时残留的租户会让下一个请求读到别人的租户， 是跨租户串数据的直接原因。
     */
    public static void clear() {
        HOLDER.remove();
        log.debug("清理租户上下文");
    }
}
