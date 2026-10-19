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
package io.github.nephets.stockyard.sample;

import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.engine.StockyardEngine;
import io.github.nephets.stockyard.core.idempotency.DeductionRecord;
import io.github.nephets.stockyard.core.reservation.Reservation;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 启动即演示三类能力：预占—确认—扣减、超时释放、幂等扣减防超卖。 */
@Component
public class StockyardDemo implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StockyardDemo.class);
    private final StockyardEngine engine;

    public StockyardDemo(StockyardEngine engine) {
        this.engine = engine;
    }

    @Override
    public void run(String... args) throws Exception {
        StockyardTenant tenant = StockyardTenant.of("demo");
        Sku sku = Sku.of("SKU-1");
        Warehouse wh = Warehouse.of("WH-1");

        engine.stockIn(sku, wh, 100);
        log.info("入库 100，当前可用 {}", engine.position(sku, wh).available());

        Reservation r = engine.reserve("R1", tenant, sku, wh, 10, null);
        log.info("预占 R1 状态 {}，可用 {}", r.status(), engine.position(sku, wh).available());
        engine.confirm(r.id(), Instant.now());
        log.info("确认 R1 后可用 {}", engine.position(sku, wh).available());

        DeductionRecord d1 = engine.deduct("D1", tenant, sku, wh, 5, Instant.now());
        DeductionRecord d2 = engine.deduct("D1", tenant, sku, wh, 5, Instant.now());
        log.info("幂等扣减两次结果一致：{}", d1.equals(d2));

        engine.reserve("R2", tenant, sku, wh, 1, 1L);
        Thread.sleep(1100);
        var expired = engine.expireDue(Instant.now());
        log.info("超时失效预占：{}", expired);
    }
}
