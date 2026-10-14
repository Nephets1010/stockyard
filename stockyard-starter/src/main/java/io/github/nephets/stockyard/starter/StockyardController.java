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

import io.github.nephets.stockyard.core.OversellException;
import io.github.nephets.stockyard.core.StockyardException;
import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.engine.StockyardEngine;
import io.github.nephets.stockyard.core.reservation.Reservation;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.StockPosition;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 库存与预占的 HTTP 端点。 */
@RestController
@RequestMapping("/stockyard")
public class StockyardController {

    private static final Logger log = LoggerFactory.getLogger(StockyardController.class);

    private final StockyardEngine engine;

    public StockyardController(StockyardEngine engine) {
        this.engine = engine;
    }

    private StockyardTenant tenant() {
        StockyardTenant t = StockyardTenantContext.get();
        if (t == null) {
            // 缺租户头只返回 400 而不留痕时，过滤器配置错误（例如租户头名被改）会表现为「凭空 400」，
            // 因此这里必须打一条 warn。
            log.warn("请求缺少租户上下文，无法解析租户");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少租户头");
        }
        return t;
    }

    @PostMapping("/reservations")
    public ReserveResponse reserve(@RequestBody ReserveRequest req) {
        log.info("reserve sku={} warehouse={} qty={}", req.sku(), req.warehouse(), req.qty());
        try {
            Reservation r =
                    engine.reserve(
                            UUID.randomUUID().toString(),
                            tenant(),
                            Sku.of(req.sku()),
                            Warehouse.of(req.warehouse()),
                            req.qty(),
                            req.ttlSeconds());
            return new ReserveResponse(
                    r.id(),
                    r.status().name(),
                    engine.position(Sku.of(req.sku()), Warehouse.of(req.warehouse())).available());
        } catch (OversellException e) {
            log.error(
                    "reserve oversell sku={} warehouse={} qty={}",
                    req.sku(),
                    req.warehouse(),
                    req.qty(),
                    e);
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (StockyardException e) {
            // 其余领域异常（预占缺失/冲突、状态流转非法）此前会被直接吞成 500 且不留痕；这里补日志后
            // 原样重抛，HTTP 语义保持不变，不引入需要破坏性变更才能做的状态码映射。
            log.error(
                    "reserve 失败 sku={} warehouse={} qty={}",
                    req.sku(),
                    req.warehouse(),
                    req.qty(),
                    e);
            throw e;
        }
    }

    @PostMapping("/reservations/{id}/confirm")
    public ReserveResponse confirm(@PathVariable String id) {
        log.info("confirm id={}", id);
        try {
            Reservation r = engine.confirm(id, Instant.now());
            return new ReserveResponse(
                    r.id(), r.status().name(), engine.position(r.sku(), r.warehouse()).available());
        } catch (StockyardException e) {
            log.error("confirm 失败 id={}", id, e);
            throw e;
        }
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReserveResponse cancel(
            @PathVariable String id, @RequestBody(required = false) CancelRequest req) {
        log.info("cancel id={}", id);
        try {
            Reservation r = engine.cancel(id, Instant.now(), req == null ? null : req.reason());
            return new ReserveResponse(
                    r.id(), r.status().name(), engine.position(r.sku(), r.warehouse()).available());
        } catch (StockyardException e) {
            log.error("cancel 失败 id={}", id, e);
            throw e;
        }
    }

    @PostMapping("/reservations/expire")
    public List<String> expire() {
        log.info("expire");
        return engine.expireDue(Instant.now());
    }

    @GetMapping("/positions")
    public PositionResponse position(@RequestParam String sku, @RequestParam String warehouse) {
        log.info("position sku={} warehouse={}", sku, warehouse);
        StockPosition p = engine.position(Sku.of(sku), Warehouse.of(warehouse));
        return new PositionResponse(
                p.sku().code(), p.warehouse().code(), p.onHand(), p.allocated(), p.available());
    }

    @PostMapping("/deductions")
    public DeductResponse deduct(@RequestBody DeductRequest req) {
        log.info(
                "deduct requestId={} sku={} warehouse={} qty={}",
                req.requestId(),
                req.sku(),
                req.warehouse(),
                req.qty());
        try {
            var rec =
                    engine.deduct(
                            req.requestId(),
                            tenant(),
                            Sku.of(req.sku()),
                            Warehouse.of(req.warehouse()),
                            req.qty(),
                            Instant.now());
            long avail =
                    engine.position(Sku.of(req.sku()), Warehouse.of(req.warehouse())).available();
            return new DeductResponse(rec.requestId(), rec.status().name(), avail);
        } catch (OversellException e) {
            log.error(
                    "deduct oversell requestId={} sku={} warehouse={} qty={}",
                    req.requestId(),
                    req.sku(),
                    req.warehouse(),
                    req.qty(),
                    e);
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (StockyardException e) {
            log.error(
                    "deduct 失败 requestId={} sku={} warehouse={} qty={}",
                    req.requestId(),
                    req.sku(),
                    req.warehouse(),
                    req.qty(),
                    e);
            throw e;
        }
    }
}
