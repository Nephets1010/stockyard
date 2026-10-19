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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户线程上下文的绑定与清理。
 *
 * <p>为什么必须测「清理」：容器线程是复用的。如果某个请求没有在 finally 里清掉租户，下一个复用到
 * 这条线程的请求就会读到上一个请求的租户——这是跨租户串数据的直接原因，而且在功能测试里几乎 不可能被发现（只有并发压测才会偶发）。
 */
class StockyardTenantContextTest {

    @AfterEach
    void cleanup() {
        StockyardTenantContext.clear();
    }

    @Test
    void a_bound_tenant_is_readable() {
        StockyardTenantContext.set(StockyardTenant.of("acme"));
        assertThat(StockyardTenantContext.get()).isEqualTo(StockyardTenant.of("acme"));
    }

    @Test
    void an_unbound_thread_has_no_tenant() {
        assertThat(StockyardTenantContext.get()).isNull();
    }

    @Test
    void clear_removes_the_binding() {
        StockyardTenantContext.set(StockyardTenant.of("acme"));
        StockyardTenantContext.clear();
        assertThat(StockyardTenantContext.get()).isNull();
    }

    @Test
    void clear_is_idempotent() {
        // 过滤器在 finally 里无条件调用 clear；重复清理必须安全。
        StockyardTenantContext.clear();
        StockyardTenantContext.clear();
        assertThat(StockyardTenantContext.get()).isNull();
    }

    @Test
    void rebinding_replaces_the_previous_tenant() {
        StockyardTenantContext.set(StockyardTenant.of("acme"));
        StockyardTenantContext.set(StockyardTenant.of("globex"));
        assertThat(StockyardTenantContext.get()).isEqualTo(StockyardTenant.of("globex"));
    }

    @Test
    void the_context_does_not_leak_into_another_thread() throws Exception {
        StockyardTenantContext.set(StockyardTenant.of("acme"));
        AtomicReference<StockyardTenant> seen = new AtomicReference<>();
        Thread t =
                new Thread(
                        () -> {
                            seen.set(StockyardTenantContext.get());
                            StockyardTenantContext.set(StockyardTenant.of("globex"));
                        });
        t.start();
        t.join();

        // 新线程看不到主线程绑的租户（ThreadLocal 语义），主线程也不受子线程影响。
        assertThat(seen.get()).isNull();
        assertThat(StockyardTenantContext.get()).isEqualTo(StockyardTenant.of("acme"));
    }

    @Test
    void a_null_tenant_can_be_bound_but_is_not_a_valid_business_value() {
        // 上下文本身不校验（它只是搬运工）；真正的校验在 StockyardTenant.of 里。
        assertThatThrownBy(() -> StockyardTenant.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
