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

import static org.assertj.core.api.Assertions.assertThat;

class DeductionRecordTest {

    private final StockyardTenant tenant = StockyardTenant.of("t");
    private final Sku sku = Sku.of("S");
    private final Warehouse wh = Warehouse.of("W");

    @Test
    void success_record() {
        DeductionRecord r = DeductionRecord.success("r1", tenant, sku, wh, 5);
        assertThat(r.requestId()).isEqualTo("r1");
        assertThat(r.status()).isEqualTo(DeductionStatus.SUCCESS);
        assertThat(r.qty()).isEqualTo(5);
    }

    @Test
    void failed_record() {
        DeductionRecord r = DeductionRecord.failed("r1", tenant, sku, wh, 5, "no stock");
        assertThat(r.status()).isEqualTo(DeductionStatus.FAILED);
        assertThat(r.error()).isEqualTo("no stock");
    }

    @Test
    void equals_by_requestId() {
        assertThat(DeductionRecord.success("r1", tenant, sku, wh, 5))
                .isEqualTo(DeductionRecord.failed("r1", tenant, sku, wh, 5, "x"));
    }
}
