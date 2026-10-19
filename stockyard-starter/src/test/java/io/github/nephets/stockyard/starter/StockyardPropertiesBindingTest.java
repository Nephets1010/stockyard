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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置属性与 yml 的绑定。
 *
 * <p>为什么默认值要被单独断言：{@code stockyard.enabled} 与 {@code stockyard.tenant-header} 都是
 * 「不配也能跑」的属性。默认值一旦被改动，用户的现存部署会静默换行为——这里把默认值钉死， 顺便钉住「空值必须回退而不是照单全收」这条安全策略。
 */
class StockyardPropertiesBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(StockyardAutoConfiguration.class));

    @Test
    void out_of_the_box_defaults() {
        StockyardProperties p = new StockyardProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getTenantHeader()).isEqualTo("X-Tenant-Id");
        assertThat(StockyardProperties.DEFAULT_TENANT_HEADER).isEqualTo("X-Tenant-Id");
    }

    @Test
    void tenant_header_is_bound_from_yml() {
        runner.withPropertyValues("stockyard.tenant-header=X-Org-Id")
                .run(
                        ctx ->
                                assertThat(ctx.getBean(StockyardProperties.class).getTenantHeader())
                                        .isEqualTo("X-Org-Id"));
    }

    @Test
    void enabled_is_bound_from_yml() {
        // 关闭开关时整个自动装配都不生效（包括属性 Bean），而不是挂一堆 Bean 在里面。
        runner.withPropertyValues("stockyard.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).doesNotHaveBean(StockyardProperties.class);
                            assertThat(ctx).doesNotHaveBean(StockyardTenantFilter.class);
                        });
        runner.withPropertyValues("stockyard.enabled=true")
                .run(
                        ctx ->
                                assertThat(ctx.getBean(StockyardProperties.class).isEnabled())
                                        .isTrue());
    }

    @Test
    void a_blank_value_in_yml_falls_back_to_the_safe_default() {
        // yml 里写成 `tenant-header:`（空）比不写更危险：过滤器会拿一个空头名去取 header，
        // 结果所有请求都解析不出租户，表现为全量 400 且日志里没有任何线索。必须回退。
        runner.withPropertyValues("stockyard.tenant-header=")
                .run(
                        ctx ->
                                assertThat(ctx.getBean(StockyardProperties.class).getTenantHeader())
                                        .isEqualTo(StockyardProperties.DEFAULT_TENANT_HEADER));
        new StockyardProperties().setTenantHeader("   ");
        assertThat(new StockyardProperties().getTenantHeader())
                .isEqualTo(StockyardProperties.DEFAULT_TENANT_HEADER);
    }

    @Test
    void a_valid_header_survives_a_later_blank_assignment() {
        // 回退只应该在「新值是空的」时发生，不能把已经配好的值清掉。
        StockyardProperties p = new StockyardProperties();
        p.setTenantHeader("X-Org");
        p.setTenantHeader(" ");
        assertThat(p.getTenantHeader()).isEqualTo("X-Org");
    }

    @Test
    void a_null_header_is_ignored_rather_than_stored() {
        StockyardProperties p = new StockyardProperties();
        p.setTenantHeader("X-Org");
        p.setTenantHeader(null);
        assertThat(p.getTenantHeader()).isEqualTo("X-Org");
    }

    @Test
    void the_filter_picks_up_the_configured_header() {
        StockyardProperties p = new StockyardProperties();
        p.setTenantHeader("X-Org-Id");
        assertThat(new StockyardTenantFilter(p)).isNotNull();
    }
}
