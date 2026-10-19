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
package io.github.nephets.stockyard.core.engine;

import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.idempotency.DeductionStatus;
import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.reservation.ReservationStatus;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引擎「时间观」的确定性测试。
 *
 * <p>为什么这组测试能替代 {@code Thread.sleep}：唯一的依赖时间的业务规则是「预占超过 TTL 未确认就 自动释放」。把 {@link Clock}
 * 注入到构造期之后，测试可以用一个可手动推进的时钟，把「30 分钟」压缩成 一次 {@code advance(1801)}，断言因此变成确定性的——既不慢，也不会因为 CI 繁忙而随机失败。
 */
class StockyardEngineClockTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    /** 可手动推进的时钟：让「过了一段时间」成为一次方法调用，而不是一次 sleep。 */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static StockyardEngine newEngine(Clock clock) {
        return new StockyardEngine(
                new InMemoryReservationStore(),
                new InMemoryStockPositionStore(),
                new InMemoryDeductionStore(),
                clock);
    }

    @Test
    void the_engine_exposes_the_clock_it_was_built_with() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        assertThat(newEngine(clock).clock()).isSameAs(clock);
    }

    @Test
    void the_default_constructor_falls_back_to_the_system_clock() {
        // 不传时钟时不能变成 null：引擎内部所有 TTL 判定都依赖它。
        StockyardEngine e =
                new StockyardEngine(
                        new InMemoryReservationStore(),
                        new InMemoryStockPositionStore(),
                        new InMemoryDeductionStore());
        assertThat(e.clock()).isNotNull();
        assertThat(e.clock().instant()).isNotNull();
    }

    @Test
    void reserve_stamps_created_at_from_the_injected_clock() {
        StockyardEngine e = newEngine(Clock.fixed(T0, ZoneOffset.UTC));
        e.stockIn(sku, wh, 10);
        assertThat(e.reserve("R1", tenant, sku, wh, 1, null).createdAt()).isEqualTo(T0);
    }

    @Test
    void a_reservation_within_its_ttl_is_not_expired() {
        MutableClock clock = new MutableClock(T0);
        StockyardEngine e = newEngine(clock);
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, 1800L);
        clock.advanceSeconds(1799);
        assertThat(e.expireDue()).isEmpty();
        assertThat(e.position(sku, wh).allocated()).isEqualTo(4);
    }

    @Test
    void a_reservation_past_its_ttl_is_released_deterministically() {
        MutableClock clock = new MutableClock(T0);
        StockyardEngine e = newEngine(clock);
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, 1800L);
        clock.advanceSeconds(1801);
        // 一次 advance 就等于「等了 30 分钟」，全程不 sleep。
        assertThat(e.expireDue()).containsExactly("R1");
        assertThat(e.position(sku, wh).allocated()).isZero();
        assertThat(e.position(sku, wh).available()).isEqualTo(10);
    }

    @Test
    void expireDue_no_arg_overload_uses_the_injected_clock() {
        MutableClock clock = new MutableClock(T0);
        StockyardEngine e = newEngine(clock);
        e.stockIn(sku, wh, 10);
        e.reserve("R1", tenant, sku, wh, 4, 60L);
        clock.advanceSeconds(61);
        // 不传 Instant 的重载必须和传 Instant 的版本看到同一个「现在」。
        assertThat(e.expireDue()).containsExactly("R1");
        assertThat(e.expireDue(clock.instant())).isEmpty(); // 已经回收过了
    }

    @Test
    void confirm_stamps_confirmed_at_from_the_injected_clock() {
        MutableClock clock = new MutableClock(T0);
        StockyardEngine e = newEngine(clock);
        e.stockIn(sku, wh, 10);
        String id = e.reserve("R1", tenant, sku, wh, 4, null).id();
        clock.advanceSeconds(5);
        assertThat(e.confirm(id).confirmedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(e.confirm(id).status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void cancel_stamps_cancelled_at_from_the_injected_clock() {
        MutableClock clock = new MutableClock(T0);
        StockyardEngine e = newEngine(clock);
        e.stockIn(sku, wh, 10);
        String id = e.reserve("R1", tenant, sku, wh, 4, null).id();
        clock.advanceSeconds(7);
        assertThat(e.cancel(id, "用户取消").cancelledAt()).isEqualTo(T0.plusSeconds(7));
    }

    @Test
    void deduct_stamps_the_record_from_the_injected_clock() {
        StockyardEngine e = newEngine(Clock.fixed(T0, ZoneOffset.UTC));
        e.stockIn(sku, wh, 10);
        assertThat(e.deduct("D1", tenant, sku, wh, 3).createdAt()).isEqualTo(T0);
        assertThat(e.deduct("D2", tenant, sku, wh, 999).status()).isEqualTo(DeductionStatus.FAILED);
    }

    @Test
    void null_clock_is_rejected_at_construction_time() {
        // 构造期拒绝：null 时钟会在几十行之后的 TTL 判定里变成莫名其妙的 NPE。
        assertThatThrownBy(
                        () ->
                                new StockyardEngine(
                                        new InMemoryReservationStore(),
                                        new InMemoryStockPositionStore(),
                                        new InMemoryDeductionStore(),
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_stores_are_rejected_at_construction_time() {
        assertThatThrownBy(
                        () ->
                                new StockyardEngine(
                                        null,
                                        new InMemoryStockPositionStore(),
                                        new InMemoryDeductionStore()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new StockyardEngine(
                                        new InMemoryReservationStore(),
                                        null,
                                        new InMemoryDeductionStore()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new StockyardEngine(
                                        new InMemoryReservationStore(),
                                        new InMemoryStockPositionStore(),
                                        null))
                .isInstanceOf(NullPointerException.class);
    }
}
