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
package io.github.nephets.stockyard.core.reservation;

import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    @Test
    void pending_activates() {
        Reservation r = Reservation.pending(tenant, sku, wh, 3, null).activate(Instant.now());
        assertThat(r.status()).isEqualTo(ReservationStatus.ACTIVE);
    }

    @Test
    void activate_expired_throws() {
        Reservation r = Reservation.pending(tenant, sku, wh, 3, 10L);
        assertThatThrownBy(() -> r.activate(r.createdAt().plusSeconds(20)))
                .isInstanceOf(ReservationException.class);
    }

    @Test
    void confirm_from_active() {
        Reservation r =
                Reservation.pending(tenant, sku, wh, 3, null)
                        .activate(Instant.now())
                        .confirm(Instant.now());
        assertThat(r.status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void confirm_from_pending_throws() {
        Reservation r = Reservation.pending(tenant, sku, wh, 3, null);
        assertThatThrownBy(() -> r.confirm(Instant.now())).isInstanceOf(ReservationException.class);
    }

    @Test
    void cancel_releases() {
        Reservation r =
                Reservation.pending(tenant, sku, wh, 3, null)
                        .activate(Instant.now())
                        .cancel(Instant.now(), "no need");
        assertThat(r.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.cancelReason()).isEqualTo("no need");
    }

    @Test
    void cancel_confirmed_throws() {
        Reservation r =
                Reservation.pending(tenant, sku, wh, 3, null)
                        .activate(Instant.now())
                        .confirm(Instant.now());
        assertThatThrownBy(() -> r.cancel(Instant.now(), null))
                .isInstanceOf(ReservationException.class);
    }

    @Test
    void expire_sets_expired() {
        Reservation r =
                Reservation.pending(tenant, sku, wh, 3, null)
                        .activate(Instant.now())
                        .expire(Instant.now());
        assertThat(r.status()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void isExpired_respects_ttl() {
        Reservation r = Reservation.pending(tenant, sku, wh, 3, 5L);
        assertThat(r.isExpired(r.createdAt().plusSeconds(3))).isFalse();
        assertThat(r.isExpired(r.createdAt().plusSeconds(6))).isTrue();
    }

    @Test
    void no_ttl_never_expires() {
        Reservation r = Reservation.pending(tenant, sku, wh, 3, null);
        assertThat(r.isExpired(r.createdAt().plusSeconds(1000))).isFalse();
    }

    @Test
    void equals_by_id() {
        Reservation a = Reservation.pending(tenant, sku, wh, 3, null);
        Reservation b = a.toBuilder().build();
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }
}
