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
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 从请求头解析租户并放入线程上下文，请求结束后清理。 */
public class StockyardTenantFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(StockyardTenantFilter.class);

    private final String tenantHeader;

    public StockyardTenantFilter(StockyardProperties properties) {
        this.tenantHeader = Objects.requireNonNull(properties, "properties").getTenantHeader();
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        log.debug("tenant filter entry uri={}", uri);
        String value = request.getHeader(tenantHeader);
        if (value != null && !value.isBlank()) {
            StockyardTenantContext.set(StockyardTenant.of(value.trim()));
        } else {
            // 本过滤器对所有 URL 生效，非 stockyard 请求本来就不带租户头，用 debug 避免常态刷日志；
            // 真正需要租户的端点会在控制器里用 warn 报「缺少租户上下文」。
            log.debug("请求未携带租户头 {} uri={}", tenantHeader, uri);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            StockyardTenantContext.clear();
            log.debug("tenant filter exit uri={}", uri);
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
