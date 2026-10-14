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

class EvictionTrackerTest {

    @Test
    void starts_at_zero() {
        assertThat(new EvictionTracker("T").count()).isZero();
    }

    @Test
    void counts_every_eviction() {
        EvictionTracker t = new EvictionTracker("T");
        for (int i = 1; i <= 5; i++) {
            t.onEvict();
            assertThat(t.count()).isEqualTo(i);
        }
    }

    @Test
    void counts_past_report_interval_without_resetting() {
        EvictionTracker t = new EvictionTracker("T");
        long n = EvictionTracker.REPORT_INTERVAL * 2L + 3L;
        for (long i = 0; i < n; i++) {
            t.onEvict();
        }
        // 聚合打点只是节流日志，计数本身必须单调递增、绝不回绕，否则运维会低估淘汰规模。
        assertThat(t.count()).isEqualTo(n);
    }

    @Test
    void toString_contains_store_name_and_count() {
        EvictionTracker t = new EvictionTracker("MyStore");
        t.onEvict();
        assertThat(t.toString()).contains("MyStore").contains("count=1");
    }
}
