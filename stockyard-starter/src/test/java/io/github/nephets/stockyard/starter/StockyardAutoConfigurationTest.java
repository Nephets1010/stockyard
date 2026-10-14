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
package io.github.nephets.stockyard.starter;

import io.github.nephets.stockyard.core.engine.StockyardEngine;
import io.github.nephets.stockyard.core.idempotency.DeductionStore;
import io.github.nephets.stockyard.core.reservation.Reservation;
import io.github.nephets.stockyard.core.store.ReservationStore;
import io.github.nephets.stockyard.core.store.StockPositionStore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StockyardAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(StockyardAutoConfiguration.class));

    @Test
    void registers_beans_by_default() {
        runner.run(
                ctx -> {
                    assertThat(ctx).hasSingleBean(StockyardEngine.class);
                    assertThat(ctx).hasSingleBean(ReservationStore.class);
                    assertThat(ctx).hasSingleBean(StockPositionStore.class);
                    assertThat(ctx).hasSingleBean(DeductionStore.class);
                    assertThat(ctx).hasSingleBean(StockyardController.class);
                    assertThat(ctx).hasSingleBean(StockyardTenantFilter.class);
                });
    }

    @Test
    void disabled_does_not_register() {
        runner.withPropertyValues("stockyard.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).doesNotHaveBean(StockyardEngine.class);
                            assertThat(ctx).doesNotHaveBean(StockyardController.class);
                        });
    }

    @Test
    void respects_user_reservation_store() {
        runner.withUserConfiguration(CustomConfig.class)
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(ReservationStore.class);
                            assertThat(ctx.getBean(ReservationStore.class))
                                    .isInstanceOf(CustomStore.class);
                        });
    }

    static class CustomStore implements ReservationStore {
        @Override
        public Reservation save(Reservation reservation) {
            return reservation;
        }

        @Override
        public Optional<Reservation> find(String id) {
            return Optional.empty();
        }

        @Override
        public List<Reservation> findAll() {
            return List.of();
        }
    }

    @Configuration
    static class CustomConfig {
        @Bean
        ReservationStore reservationStore() {
            return new CustomStore();
        }
    }
}
