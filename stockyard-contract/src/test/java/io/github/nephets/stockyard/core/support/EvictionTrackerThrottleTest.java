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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 淘汰计数器的「可观测性」契约。
 *
 * <p>核心不变量：<strong>计数永远准确，告警可以丢</strong>。计数是业务事实（确实丢了一条数据）， 告警只是它的呈现方式；两者必须解耦，否则日志后端一挂，连「丢了多少」都查不到。
 */
class EvictionTrackerThrottleTest {

    @Test
    void a_fresh_tracker_has_never_evicted() {
        assertThat(new EvictionTracker("store").count()).isZero();
    }

    @Test
    void every_eviction_is_counted_even_beyond_the_report_interval() {
        EvictionTracker t = new EvictionTracker("store");
        long total = EvictionTracker.REPORT_INTERVAL * 3L + 7L;
        for (long i = 0; i < total; i++) {
            t.onEvict();
        }
        // 告警是节流的，但计数不是：任何时刻 count() 都必须等于真实淘汰条数。
        assertThat(t.count()).isEqualTo(total);
    }

    @Test
    void count_is_monotonic_and_never_resets() {
        EvictionTracker t = new EvictionTracker("store");
        t.onEvict();
        long first = t.count();
        t.onEvict();
        assertThat(t.count()).isEqualTo(first + 1L);
        assertThat(t.count()).isGreaterThan(0L);
    }

    @Test
    void report_interval_is_a_round_number_that_keeps_logs_readable() {
        // 10_000 是权衡：太小会刷爆日志，太大则容量打满后要等很久才有第二条证据。
        assertThat(EvictionTracker.REPORT_INTERVAL).isEqualTo(10_000L).isPositive();
    }

    @Test
    void a_null_store_name_does_not_break_counting() {
        // storeName 只用于日志定位；它缺失时不能影响唯一的硬事实（计数）。
        EvictionTracker t = new EvictionTracker(null);
        t.onEvict();
        assertThat(t.count()).isEqualTo(1L);
    }

    @Test
    void toString_is_diagnostic_friendly() {
        EvictionTracker t = new EvictionTracker("InMemoryReservationStore");
        t.onEvict();
        t.onEvict();
        assertThat(t.toString()).contains("InMemoryReservationStore").contains("count=2");
    }
}
