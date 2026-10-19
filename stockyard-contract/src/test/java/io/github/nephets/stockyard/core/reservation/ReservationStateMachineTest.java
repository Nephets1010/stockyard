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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预占状态机的完整迁移矩阵。
 *
 * <pre>
 *   PENDING --activate--> ACTIVE --confirm--> CONFIRMED (终态)
 *      |                    |
 *      +------cancel------- +------cancel--> CANCELLED (终态)
 *      |                    |
 *      +------expire--------+------expire--> EXPIRED   (终态)
 * </pre>
 *
 * <p>为什么要把 5×4 的矩阵逐格钉死：状态机的漏洞不会报错，只会让库存「悄悄少掉或悄悄多出来」。 例如若 EXPIRED 还能被
 * confirm，那么一条已被回收的预占会再扣一次库存，表现为凭空少货。这类 bug 只有穷举迁移矩阵才能在合入前拦住。
 */
class ReservationStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private Reservation pending() {
        return Reservation.pending(tenant, sku, wh, 5, null, CLOCK);
    }

    private Reservation active() {
        return pending().activate(T0);
    }

    private Reservation confirmed() {
        return active().confirm(T0);
    }

    private Reservation cancelled() {
        return active().cancel(T0, "用户取消");
    }

    private Reservation expired() {
        return active().expire(T0);
    }

    // ---------- activate：只有 PENDING 能生效 ----------

    @Test
    void pending_activate_becomes_active() {
        Reservation r = pending().activate(T0);
        assertThat(r.status()).isEqualTo(ReservationStatus.ACTIVE);
    }

    @Test
    void activate_from_active_is_rejected() {
        assertThatThrownBy(() -> active().activate(T0))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("只能在 PENDING 状态生效");
    }

    @Test
    void activate_from_confirmed_is_rejected() {
        assertThatThrownBy(() -> confirmed().activate(T0)).isInstanceOf(ReservationException.class);
    }

    @Test
    void activate_from_cancelled_is_rejected() {
        assertThatThrownBy(() -> cancelled().activate(T0)).isInstanceOf(ReservationException.class);
    }

    @Test
    void activate_from_expired_is_rejected() {
        assertThatThrownBy(() -> expired().activate(T0)).isInstanceOf(ReservationException.class);
    }

    // ---------- confirm：只有 ACTIVE 能确认 ----------

    @Test
    void active_confirm_becomes_confirmed_and_stamps_time() {
        Reservation r = active().confirm(T0.plusSeconds(10));
        assertThat(r.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(r.confirmedAt()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void confirm_from_pending_is_rejected() {
        // 未生效就确认，等于跳过 allocate 直接扣库存，是典型的「绕过状态机」信号。
        assertThatThrownBy(() -> pending().confirm(T0))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("只能在 ACTIVE 状态确认");
    }

    @Test
    void confirm_from_confirmed_is_rejected_at_the_value_object_level() {
        // 注意：引擎层对 CONFIRMED 走幂等直接返回；这里的拒绝是值对象层的兜底，防止有人绕过引擎。
        assertThatThrownBy(() -> confirmed().confirm(T0)).isInstanceOf(ReservationException.class);
    }

    @Test
    void confirm_from_cancelled_is_rejected() {
        assertThatThrownBy(() -> cancelled().confirm(T0)).isInstanceOf(ReservationException.class);
    }

    @Test
    void confirm_from_expired_is_rejected() {
        // 已超时回收的预占再被确认会二次扣减库存（库存已经被释放掉了），必须拒绝。
        assertThatThrownBy(() -> expired().confirm(T0)).isInstanceOf(ReservationException.class);
    }

    // ---------- cancel：PENDING / ACTIVE 可取消，终态不可 ----------

    @Test
    void pending_cancel_becomes_cancelled() {
        Reservation r = pending().cancel(T0, "超时未支付");
        assertThat(r.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.cancelledAt()).isEqualTo(T0);
        assertThat(r.cancelReason()).isEqualTo("超时未支付");
    }

    @Test
    void active_cancel_becomes_cancelled() {
        assertThat(active().cancel(T0, null).status()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void cancel_reason_may_be_null() {
        assertThat(active().cancel(T0, null).cancelReason()).isNull();
    }

    @Test
    void cancel_from_confirmed_is_rejected() {
        assertThatThrownBy(() -> confirmed().cancel(T0, "x"))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("已确认不可取消");
    }

    @Test
    void cancel_from_cancelled_is_rejected() {
        assertThatThrownBy(() -> cancelled().cancel(T0, "x"))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("已取消不可重复取消");
    }

    @Test
    void cancel_from_expired_is_rejected() {
        assertThatThrownBy(() -> expired().cancel(T0, "x"))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("已过期不可取消");
    }

    // ---------- expire：PENDING / ACTIVE 可超时，终态不可 ----------

    @Test
    void pending_expire_becomes_expired_and_stamps_time() {
        Reservation r = pending().expire(T0.plusSeconds(30));
        assertThat(r.status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(r.expiredAt()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    void active_expire_becomes_expired() {
        assertThat(active().expire(T0).status()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void expire_from_confirmed_is_rejected() {
        assertThatThrownBy(() -> confirmed().expire(T0))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("已确认不可超时");
    }

    @Test
    void expire_from_cancelled_is_rejected() {
        assertThatThrownBy(() -> cancelled().expire(T0))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("已取消不可超时");
    }

    @Test
    void expire_from_expired_is_rejected() {
        assertThatThrownBy(() -> expired().expire(T0))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("已超时不可重复超时");
    }

    // ---------- 已经被判过期的 PENDING 不能生效 ----------

    @Test
    void activate_is_rejected_when_the_pending_has_already_passed_its_ttl() {
        Reservation r = Reservation.pending(tenant, sku, wh, 5, 60L, CLOCK);
        assertThatThrownBy(() -> r.activate(T0.plusSeconds(61)))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("预占已过期");
    }

    @Test
    void activate_is_allowed_at_the_exact_ttl_deadline() {
        // 边界：恰好等于到期时刻仍然可以生效（isAfter 是严格大于）。
        Reservation r = Reservation.pending(tenant, sku, wh, 5, 60L, CLOCK);
        assertThat(r.activate(T0.plusSeconds(60)).status()).isEqualTo(ReservationStatus.ACTIVE);
    }

    // ---------- 迁移返回新副本，原对象不变 ----------

    @Test
    void transitions_return_new_instances_and_leave_the_original_untouched() {
        Reservation p = pending();
        Reservation a = p.activate(T0);
        Reservation c = a.confirm(T0);
        assertThat(p.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(a.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(c.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(p).isNotSameAs(a);
        assertThat(a).isNotSameAs(c);
    }

    @Test
    void cancelled_reservation_keeps_its_business_fields() {
        Reservation r = cancelled();
        assertThat(r.qty()).isEqualTo(5);
        assertThat(r.sku()).isEqualTo(sku);
        assertThat(r.warehouse()).isEqualTo(wh);
        assertThat(r.tenant()).isEqualTo(tenant);
    }
}
