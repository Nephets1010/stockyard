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
package io.github.nephets.stockyard.core.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「观测路径永不抛异常」的回归测试。
 *
 * <p>这组断言钉住的是一条<strong>纪律</strong>而不是某个具体实现：库存是钱，一条 WARN 打不出去 绝不能把已经算好的库存变更一起回滚掉。
 */
class ObservationTest {

    @BeforeEach
    void resetCounter() {
        Observation.resetForTest();
    }

    @Test
    void normal_observation_action_is_executed() {
        AtomicInteger calls = new AtomicInteger();
        Observation.run(calls::incrementAndGet);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(Observation.suppressedCount()).isZero();
    }

    @Test
    void runtime_exception_from_observation_is_swallowed() {
        // 观测代码抛异常时，调用方（业务路径）必须毫无察觉——这是本类存在的唯一理由。
        assertThatCode(
                        () ->
                                Observation.run(
                                        () -> {
                                            throw new IllegalStateException("日志后端不可用");
                                        }))
                .doesNotThrowAnyException();
        assertThat(Observation.suppressedCount()).isEqualTo(1L);
    }

    @Test
    void suppressed_failures_are_counted_so_they_stay_observable() {
        for (int i = 0; i < 3; i++) {
            Observation.run(
                    () -> {
                        throw new IllegalStateException("boom");
                    });
        }
        // 吞掉不等于忘记：丢了多少条留痕必须能被读出来。
        assertThat(Observation.suppressedCount()).isEqualTo(3L);
    }

    @Test
    void null_action_is_treated_as_a_failed_observation() {
        assertThatCode(() -> Observation.run(null)).doesNotThrowAnyException();
        assertThat(Observation.suppressedCount()).isEqualTo(1L);
    }

    @Test
    void errors_are_not_swallowed_because_the_jvm_may_be_unusable() {
        // 只吞 RuntimeException：Error 意味着 JVM 已不具备继续执行的前提，吞掉会把「应当快速失败」
        // 的场景变成难以复现的数据损坏，因此必须原样冒泡。
        assertThatThrownBy(
                        () ->
                                Observation.run(
                                        () -> {
                                            throw new StackOverflowError("深度递归");
                                        }))
                .isInstanceOf(StackOverflowError.class);
    }
}
