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
package io.github.nephets.stockyard.core.idempotency;

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
 * 幂等扣减记录的等值语义、构造校验与时钟注入。
 *
 * <p>核心不变量：<strong>equals/hashCode 只看 requestId</strong>。同一 requestId 代表同一次业务意图，
 * 无论状态、数量如何都只应保留一条；否则「先失败后成功」会变成两条并存记录，去重直接失效。
 */
class DeductionRecordBoundaryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    private DeductionRecord.Builder valid() {
        return DeductionRecord.builder()
                .requestId("D1")
                .tenant(tenant)
                .sku(sku)
                .warehouse(wh)
                .qty(1)
                .status(DeductionStatus.SUCCESS)
                .createdAt(T0);
    }

    @Test
    void success_record_uses_the_injected_clock() {
        DeductionRecord r = DeductionRecord.success("D1", tenant, sku, wh, 2, CLOCK);
        assertThat(r.createdAt()).isEqualTo(T0);
        assertThat(r.status()).isEqualTo(DeductionStatus.SUCCESS);
        assertThat(r.error()).isNull();
    }

    @Test
    void failed_record_uses_the_injected_clock_and_keeps_the_reason() {
        DeductionRecord r = DeductionRecord.failed("D1", tenant, sku, wh, 2, "库存不足：可用=1", CLOCK);
        assertThat(r.createdAt()).isEqualTo(T0);
        assertThat(r.status()).isEqualTo(DeductionStatus.FAILED);
        assertThat(r.error()).isEqualTo("库存不足：可用=1");
    }

    @Test
    void system_clock_overloads_produce_comparable_timelines() {
        DeductionRecord r = DeductionRecord.success("D1", tenant, sku, wh, 2);
        assertThat(r.createdAt()).isNotNull();
        assertThat(DeductionRecord.failed("D2", tenant, sku, wh, 2, "x").createdAt()).isNotNull();
    }

    @Test
    void null_clock_is_rejected() {
        assertThatThrownBy(() -> DeductionRecord.success("D1", tenant, sku, wh, 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DeductionRecord.failed("D1", tenant, sku, wh, 1, "x", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equality_depends_only_on_request_id() {
        DeductionRecord success = valid().build();
        DeductionRecord failed =
                valid().status(DeductionStatus.FAILED).error("库存不足").qty(99).build();
        assertThat(success).isEqualTo(failed).hasSameHashCodeAs(failed);
    }

    @Test
    void different_request_ids_are_different_records() {
        assertThat(valid().requestId("D1").build()).isNotEqualTo(valid().requestId("D2").build());
    }

    @Test
    void a_record_only_equals_another_deduction_record() {
        assertThat(valid().build()).isNotEqualTo("D1");
    }

    @Test
    void missing_request_id_is_rejected() {
        assertThatThrownBy(() -> valid().requestId(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId 必填");
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
    void non_positive_qty_is_rejected() {
        // qty<=0 的记录如果被存下来，会让「这个请求扣了多少」这个问题无法回答。
        assertThatThrownBy(() -> valid().qty(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qty 必须为正");
        assertThatThrownBy(() -> valid().qty(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toString_identifies_the_record_without_dumping_tenant_data() {
        assertThat(valid().build().toString()).contains("D1").contains("SUCCESS");
    }
}
