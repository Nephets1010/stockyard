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

import io.github.nephets.stockyard.core.engine.StockyardEngine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配对「时间源」的处理。
 *
 * <p>为什么时钟要做成一个 Bean：集成测试要验证「下单 30 分钟未支付自动释放」，就必须能把时间往前拨。 只要注册一个 {@code Clock}
 * Bean，整个库存引擎的时间观就被替换——这是本库可测试性的关键接口。
 */
class StockyardAutoConfigurationClockTest {

    private static final Instant FROZEN = Instant.parse("2026-01-01T00:00:00Z");

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(StockyardAutoConfiguration.class));

    @Test
    void a_default_clock_bean_is_registered() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(Clock.class));
    }

    @Test
    void the_default_clock_is_the_system_clock() {
        runner.run(
                ctx -> {
                    Clock clock = ctx.getBean(Clock.class);
                    Instant before = clock.instant();
                    assertThat(before).isNotNull().isBeforeOrEqualTo(Instant.now());
                });
    }

    @Test
    void a_user_clock_bean_wins_over_the_default() {
        // 用户注册 Clock 后自动装配必须让位：否则集成测试永远无法冻结时间。
        runner.withUserConfiguration(FrozenClockConfig.class)
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(Clock.class);
                            assertThat(ctx.getBean(Clock.class).instant()).isEqualTo(FROZEN);
                        });
    }

    @Test
    void the_engine_is_built_with_the_injected_clock() {
        runner.withUserConfiguration(FrozenClockConfig.class)
                .run(
                        ctx -> {
                            StockyardEngine engine = ctx.getBean(StockyardEngine.class);
                            assertThat(engine.clock()).isSameAs(ctx.getBean(Clock.class));
                            assertThat(engine.clock().instant()).isEqualTo(FROZEN);
                        });
    }

    @Test
    void disabling_the_starter_removes_the_clock_bean_too() {
        // stockyard.enabled=false 时不该往用户的上下文里塞任何 Bean，包括 Clock——
        // 用户可能自己有一个 Clock，被抢占会很难排查。
        runner.withPropertyValues("stockyard.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).doesNotHaveBean(StockyardEngine.class);
                            assertThat(ctx).doesNotHaveBean(Clock.class);
                        });
    }

    @Configuration
    static class FrozenClockConfig {
        @Bean
        Clock frozenClock() {
            return Clock.fixed(FROZEN, ZoneOffset.UTC);
        }
    }
}
