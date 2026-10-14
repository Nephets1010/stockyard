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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** stockyard 自动装配配置。 */
@ConfigurationProperties(prefix = "stockyard")
public class StockyardProperties {

    private static final Logger log = LoggerFactory.getLogger(StockyardProperties.class);

    /** 租户头的安全默认值：yml 里配成空值时回退到它，避免过滤器拿到空头名后所有请求都解析不出租户。 */
    public static final String DEFAULT_TENANT_HEADER = "X-Tenant-Id";

    private boolean enabled = true;
    private String tenantHeader = DEFAULT_TENANT_HEADER;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTenantHeader() {
        return tenantHeader;
    }

    public void setTenantHeader(String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) {
            // 空白头名会让所有请求都解析不出租户（表现为全量 400），属于配置错误：回退到安全默认值并告警。
            log.warn(
                    "stockyard.tenant-header 配置为空，回退到默认值 {}；当前值保持 {}",
                    DEFAULT_TENANT_HEADER,
                    this.tenantHeader);
            return;
        }
        this.tenantHeader = tenantHeader;
    }
}
