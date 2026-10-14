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
import io.github.nephets.stockyard.core.idempotency.InMemoryDeductionStore;
import io.github.nephets.stockyard.core.store.InMemoryReservationStore;
import io.github.nephets.stockyard.core.store.InMemoryStockPositionStore;
import io.github.nephets.stockyard.core.store.ReservationStore;
import io.github.nephets.stockyard.core.store.StockPositionStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** stockyard 自动装配：注册存储、引擎、租户过滤器与控制器。 */
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "stockyard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(StockyardProperties.class)
public class StockyardAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StockyardAutoConfiguration.class);

    /**
     * 各 Bean 创建点都留一条日志：默认存储都是「内存 + 容量上限 + LRU 淘汰」的实现，一旦被装配进生产
     * 进程却没有被用户替换，事后需要能从启动日志确认这一点（否则容量打满导致的静默淘汰无从查起）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ReservationStore reservationStore() {
        log.debug(
                "装配默认 ReservationStore=InMemoryReservationStore，容量上限={}",
                InMemoryReservationStore.DEFAULT_CAPACITY);
        return new InMemoryReservationStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public StockPositionStore stockPositionStore() {
        log.debug(
                "装配默认 StockPositionStore=InMemoryStockPositionStore，容量上限={}",
                InMemoryStockPositionStore.DEFAULT_CAPACITY);
        return new InMemoryStockPositionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeductionStore deductionStore() {
        log.debug(
                "装配默认 DeductionStore=InMemoryDeductionStore，容量上限={}",
                InMemoryDeductionStore.DEFAULT_CAPACITY);
        return new InMemoryDeductionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public StockyardEngine stockyardEngine(
            ReservationStore reservationStore,
            StockPositionStore stockPositionStore,
            DeductionStore deductionStore) {
        log.info("装配 StockyardEngine");
        return new StockyardEngine(reservationStore, stockPositionStore, deductionStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public StockyardTenantFilter stockyardTenantFilter(StockyardProperties properties) {
        log.info("装配 StockyardTenantFilter，租户头={}", properties.getTenantHeader());
        return new StockyardTenantFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public StockyardController stockyardController(StockyardEngine engine) {
        log.info("装配 StockyardController");
        return new StockyardController(engine);
    }
}
