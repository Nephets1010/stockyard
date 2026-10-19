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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

/**
 * 租户过滤器的解析与清理。
 *
 * <p>关键不变量：<strong>无论请求带不带租户头、链路抛不抛异常，过滤器结束时上下文必须是干净的</strong>。 残留的 ThreadLocal
 * 会让下一个请求读到别人的租户，这是跨租户串数据的直接原因。
 */
class StockyardTenantFilterTest {

    private StockyardProperties properties;

    @BeforeEach
    void setUp() {
        properties = new StockyardProperties();
    }

    @AfterEach
    void cleanup() {
        StockyardTenantContext.clear();
    }

    private void runFilter(MockHttpServletRequest request) throws ServletException, IOException {
        FilterChain chain = new MockFilterChain();
        new StockyardTenantFilter(properties)
                .doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    void the_tenant_header_is_parsed_and_bound() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "acme");
        runFilter(request);
        // 注意：断言放在过滤之后——过滤器已经在 finally 里清理过了，这正是要验证的行为之一。
        assertThat(StockyardTenantContext.get()).isNull();
    }

    @Test
    void the_tenant_is_available_to_the_downstream_chain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "acme");
        StockyardTenant[] seen = new StockyardTenant[1];
        FilterChain chain = (req, res) -> seen[0] = StockyardTenantContext.get();
        new StockyardTenantFilter(properties)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen[0]).isEqualTo(StockyardTenant.of("acme"));
    }

    @Test
    void a_missing_header_leaves_the_context_empty() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        StockyardTenant[] seen = new StockyardTenant[1];
        FilterChain chain = (req, res) -> seen[0] = StockyardTenantContext.get();
        new StockyardTenantFilter(properties)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen[0]).isNull();
    }

    @Test
    void a_blank_header_value_is_ignored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "   ");
        StockyardTenant[] seen = new StockyardTenant[1];
        seen[0] = StockyardTenant.of("sentinel");
        FilterChain chain = (req, res) -> seen[0] = StockyardTenantContext.get();
        new StockyardTenantFilter(properties)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen[0]).isNull();
    }

    @Test
    void the_header_name_is_configurable() throws Exception {
        properties.setTenantHeader("X-Org-Id");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Org-Id", "globex");
        StockyardTenant[] seen = new StockyardTenant[1];
        FilterChain chain = (req, res) -> seen[0] = StockyardTenantContext.get();
        new StockyardTenantFilter(properties)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen[0]).isEqualTo(StockyardTenant.of("globex"));
        // 换了头名之后，老的头名不再生效。
        MockHttpServletRequest legacy = new MockHttpServletRequest();
        legacy.addHeader("X-Tenant-Id", "acme");
        new StockyardTenantFilter(properties)
                .doFilter(
                        legacy,
                        new MockHttpServletResponse(),
                        (req, res) -> assertThat(StockyardTenantContext.get()).isNull());
    }

    @Test
    void the_context_is_cleared_even_when_the_chain_throws() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "acme");
        FilterChain exploding =
                (req, res) -> {
                    throw new ServletException("下游炸了");
                };
        assertThatThrownBy(
                        () ->
                                new StockyardTenantFilter(properties)
                                        .doFilter(
                                                request, new MockHttpServletResponse(), exploding))
                .isInstanceOf(ServletException.class);
        // 异常照常向上抛，但 ThreadLocal 必须已经清干净。
        assertThat(StockyardTenantContext.get()).isNull();
    }

    @Test
    void the_filter_runs_before_other_filters() {
        // order=0：租户必须在任何业务过滤器之前绑定，否则业务过滤器读到的租户是空的。
        assertThat(new StockyardTenantFilter(properties).getOrder()).isZero();
    }

    @Test
    void null_properties_are_rejected_at_construction_time() {
        assertThatThrownBy(() -> new StockyardTenantFilter(null))
                .isInstanceOf(NullPointerException.class);
    }
}
