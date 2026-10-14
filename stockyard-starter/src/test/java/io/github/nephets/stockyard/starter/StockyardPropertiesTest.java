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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockyardPropertiesTest {

    @Test
    void defaults() {
        StockyardProperties p = new StockyardProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getTenantHeader()).isEqualTo("X-Tenant-Id");
    }

    @Test
    void setters() {
        StockyardProperties p = new StockyardProperties();
        p.setEnabled(false);
        p.setTenantHeader("X-Org");
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getTenantHeader()).isEqualTo("X-Org");
    }

    @Test
    void blank_tenant_header_falls_back_to_safe_default() {
        // 空头名会让过滤器的 getHeader 永远拿不到值，表现为全量 400；必须回退到默认值而不是照单全收。
        StockyardProperties p = new StockyardProperties();
        p.setTenantHeader("  ");
        assertThat(p.getTenantHeader()).isEqualTo(StockyardProperties.DEFAULT_TENANT_HEADER);

        p.setTenantHeader("X-Org");
        p.setTenantHeader(null);
        assertThat(p.getTenantHeader()).isEqualTo("X-Org");
    }
}
