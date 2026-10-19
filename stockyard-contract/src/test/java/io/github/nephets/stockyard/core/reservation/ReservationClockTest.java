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
 * 预占时间线的确定性测试：所有「现在几点」都来自注入的 {@link Clock}。
 *
 * <p>为什么这一组测试能存在：TTL 过期是库存系统里唯一依赖时间的业务规则。用 {@code Instant.now()} 的写法，验证「下单 30 分钟未支付自动释放」只能靠 {@code
 * Thread.sleep(31 * 60 * 1000)}——慢到没法 进 CI；或者把 TTL 调成几百毫秒去赌线程调度——在 CI 繁忙时随机失败。注入固定时钟后，同样的规则
 * 可以在一毫秒内被确定性验证。
 */
class ReservationClockTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void created_at_comes_from_the_injected_clock() {
        Reservation r = Reservation.pending(tenant, sku, wh, 1, null, at(T0));
        assertThat(r.createdAt()).isEqualTo(T0);
    }

    @Test
    void expires_at_is_created_at_plus_ttl() {
        Reservation r = Reservation.pending(tenant, sku, wh, 1, 1800L, at(T0));
        assertThat(r.expiresAt()).isEqualTo(T0.plusSeconds(1800L));
    }

    @Test
    void null_ttl_means_never_expires() {
        Reservation r = Reservation.pending(tenant, sku, wh, 1, null, at(T0));
        assertThat(r.expiresAt()).isNull();
        assertThat(r.isExpired(T0.plusSeconds(10_000_000L))).isFalse();
    }

    @Test
    void zero_ttl_expires_only_after_the_deadline_is_passed() {
        Reservation r = Reservation.pending(tenant, sku, wh, 1, 0L, at(T0));
        assertThat(r.expiresAt()).isEqualTo(T0);
        // 判定用的是 isAfter（严格大于）：恰好等于到期时刻仍然有效，否则 TTL=0 的预占永远无法创建。
        assertThat(r.isExpired(T0)).isFalse();
        assertThat(r.isExpired(T0.plusNanos(1L))).isTrue();
    }

    @Test
    void ttl_is_measured_in_seconds_not_milliseconds() {
        Reservation r = Reservation.pending(tenant, sku, wh, 1, 30L, at(T0));
        assertThat(r.expiresAt()).isEqualTo(T0.plusSeconds(30L));
        assertThat(r.isExpired(T0.plusMillis(29_999L))).isFalse();
        assertThat(r.isExpired(T0.plusSeconds(30L).plusNanos(1L))).isTrue();
    }

    @Test
    void activate_uses_the_injected_clock_to_reject_an_already_expired_pending() {
        // 时钟推进 31 分钟后，TTL=1800s 的预占再想生效就必须被拒绝——这就是「超时未支付不能再占货」。
        Clock start = at(T0);
        Reservation r = Reservation.pending(tenant, sku, wh, 1, 1800L, start);
        Clock later = at(T0.plusSeconds(1801L));
        assertThatThrownBy(() -> r.activate(later.instant()))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("已过期");
    }

    @Test
    void system_clock_overload_still_works() {
        // 保留无 Clock 的重载是为了不破坏既有调用方；它必须仍然产出可比较的时间线。
        Reservation r = Reservation.pending(tenant, sku, wh, 1, 60L);
        assertThat(r.createdAt()).isNotNull();
        assertThat(r.expiresAt()).isEqualTo(r.createdAt().plusSeconds(60L));
    }

    @Test
    void null_clock_is_rejected_at_construction_time() {
        // 构造期拒绝 null，而不是让它变成一个 NPE 出现在几十行之后的 TTL 判定里。
        assertThatThrownBy(() -> Reservation.pending(tenant, sku, wh, 1, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
