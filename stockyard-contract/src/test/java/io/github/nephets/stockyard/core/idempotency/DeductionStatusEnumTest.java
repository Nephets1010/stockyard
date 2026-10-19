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
package io.github.nephets.stockyard.core.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扣减结果状态枚举的形状约束。
 *
 * <p>为什么「失败」也要是一个正式状态而不是抛异常：库存不足在秒杀里是常态而非故障，把它建模成 {@link #FAILED} 记录后，调用方可以用 {@code if
 * (rec.status() == FAILED)} 平静地处理，同时这条 结论会被固化下来——重试拿到的是同一个答案，而不是碰运气。
 */
class DeductionStatusEnumTest {

    @Test
    void has_exactly_success_and_failed() {
        assertThat(DeductionStatus.values())
                .containsExactly(DeductionStatus.SUCCESS, DeductionStatus.FAILED);
    }

    @Test
    void round_trips_through_name() {
        // name() 会写进 HTTP 响应体，是外部协议的一部分。
        for (DeductionStatus s : DeductionStatus.values()) {
            assertThat(DeductionStatus.valueOf(s.name())).isSameAs(s);
        }
    }
}
