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
package io.github.nephets.stockyard.core.store;

import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.reservation.Reservation;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryReservationStoreTest {

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    @Test
    void save_find_findAll() {
        InMemoryReservationStore s = new InMemoryReservationStore();
        Reservation r = Reservation.pending(tenant, sku, wh, 1, null).activate(Instant.now());
        s.save(r);
        assertThat(s.find(r.id())).contains(r);
        assertThat(s.findAll()).hasSize(1);
    }

    @Test
    void upsert_by_id() {
        InMemoryReservationStore s = new InMemoryReservationStore();
        Reservation r = Reservation.pending(tenant, sku, wh, 1, null).activate(Instant.now());
        s.save(r);
        Reservation cancelled = r.cancel(Instant.now(), null);
        s.save(cancelled);
        assertThat(s.find(r.id())).contains(cancelled);
        assertThat(s.findAll()).hasSize(1);
    }

    @Test
    void evicts_oldest_when_over_capacity() {
        InMemoryReservationStore s = new InMemoryReservationStore(3);
        Instant now = Instant.now();
        for (int i = 1; i <= 5; i++) {
            Reservation r =
                    Reservation.pending(tenant, sku, wh, 1, null).toBuilder()
                            .id("r" + i)
                            .build()
                            .activate(now);
            s.save(r);
        }
        assertThat(s.find("r1")).isEmpty(); // 最旧的预占单已被淘汰
        assertThat(s.find("r2")).isEmpty();
        assertThat(s.find("r5")).isPresent(); // 最新的预占单仍在
    }

    @Test
    void eviction_is_observable_instead_of_silent() {
        InMemoryReservationStore s = new InMemoryReservationStore(2);
        assertThat(s.evictedCount()).isZero();

        Instant now = Instant.now();
        for (int i = 1; i <= 5; i++) {
            s.save(
                    Reservation.pending(tenant, sku, wh, 1, null).toBuilder()
                            .id("r" + i)
                            .build()
                            .activate(now));
        }
        // 淘汰不能再是静默的：计数器必须反映真实淘汰条数，供运维/告警判断容量是否够用。
        assertThat(s.evictedCount()).isEqualTo(3L);
    }
}
