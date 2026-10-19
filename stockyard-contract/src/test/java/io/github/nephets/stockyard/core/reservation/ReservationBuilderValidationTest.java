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

/**
 * 预占构造期的必填校验与等值语义。
 *
 * <p>为什么 qty 必须为正：qty=0 的预占是一条「永远不会被确认也永远不会释放库存」的脏数据， 它会一直占着 reservationStore 的容量；qty<0
 * 则会在确认时把库存加上去（凭空造货）。
 */
class ReservationBuilderValidationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private Reservation.Builder valid() {
        return Reservation.builder()
                .id("R1")
                .tenant(tenant)
                .sku(sku)
                .warehouse(wh)
                .qty(1)
                .status(ReservationStatus.PENDING)
                .createdAt(T0);
    }

    @Test
    void valid_reservation_is_built() {
        assertThat(valid().build().id()).isEqualTo("R1");
    }

    @Test
    void missing_id_is_rejected() {
        assertThatThrownBy(() -> valid().id(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 必填");
    }

    @Test
    void missing_tenant_is_rejected() {
        assertThatThrownBy(() -> valid().tenant(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant 必填");
    }

    @Test
    void missing_sku_is_rejected() {
        assertThatThrownBy(() -> valid().sku(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku 必填");
    }

    @Test
    void missing_warehouse_is_rejected() {
        assertThatThrownBy(() -> valid().warehouse(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warehouse 必填");
    }

    @Test
    void missing_status_is_rejected() {
        assertThatThrownBy(() -> valid().status(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status 必填");
    }

    @Test
    void zero_qty_is_rejected() {
        assertThatThrownBy(() -> valid().qty(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qty 必须为正");
    }

    @Test
    void negative_qty_is_rejected() {
        assertThatThrownBy(() -> valid().qty(-5).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qty 必须为正");
    }

    @Test
    void pending_factory_rejects_non_positive_qty() {
        assertThatThrownBy(() -> Reservation.pending(tenant, sku, wh, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预占数量必须为正");
    }

    @Test
    void toBuilder_round_trips_all_fields() {
        Reservation r = Reservation.pending(tenant, sku, wh, 7, 60L).toBuilder().id("R2").build();
        Reservation copy = r.toBuilder().build();
        assertThat(copy).isEqualTo(r);
        assertThat(copy.qty()).isEqualTo(7);
        assertThat(copy.expiresAt()).isEqualTo(r.expiresAt());
    }

    @Test
    void equality_is_by_business_identity_including_status() {
        Reservation a = valid().build();
        Reservation b = valid().build();
        Reservation c = valid().status(ReservationStatus.ACTIVE).build();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        // 状态不同就不是同一条业务事实：否则「已确认」与「已取消」会在 Set 里互相覆盖。
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void equality_ignores_audit_timestamps_so_reloads_stay_equal() {
        Reservation a = valid().confirmedAt(T0).build();
        Reservation b = valid().confirmedAt(T0.plusSeconds(1)).build();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void toString_is_compact_and_safe_to_log() {
        Reservation r = valid().tenant(StockyardTenant.of("acme-corp")).build();
        assertThat(r.toString()).contains("R1").contains("PENDING").doesNotContain("acme-corp");
    }
}
