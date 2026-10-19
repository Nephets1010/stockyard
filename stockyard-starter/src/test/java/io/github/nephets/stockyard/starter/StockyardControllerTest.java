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
import io.github.nephets.stockyard.core.StockyardTenant;
import io.github.nephets.stockyard.core.engine.StockyardEngine;
import io.github.nephets.stockyard.core.reservation.Reservation;
import io.github.nephets.stockyard.core.sku.Sku;
import io.github.nephets.stockyard.core.sku.StockPosition;
import io.github.nephets.stockyard.core.sku.Warehouse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockyardController.class)
class StockyardControllerTest {

    @Autowired MockMvc mvc;
    @MockBean StockyardEngine engine;

    @BeforeEach
    void setTenant() {
        StockyardTenantContext.set(StockyardTenant.of("t"));
    }

    @AfterEach
    void clear() {
        StockyardTenantContext.clear();
    }

    @Test
    void reserve_missing_tenant_is_400() throws Exception {
        StockyardTenantContext.clear();
        mvc.perform(
                        post("/stockyard/reservations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sku\":\"S\",\"warehouse\":\"W\",\"qty\":1}"))
                .andExpect(status().isBadRequest());
        StockyardTenantContext.set(StockyardTenant.of("t"));
    }

    @Test
    void reserve_ok() throws Exception {
        Sku sku = Sku.of("S");
        Warehouse wh = Warehouse.of("W");
        Reservation r =
                Reservation.pending(StockyardTenant.of("t"), sku, wh, 1, null)
                        .activate(Instant.now());
        when(engine.reserve(any(), any(), any(), any(), anyLong(), any())).thenReturn(r);
        when(engine.position(any(), any())).thenReturn(StockPosition.of(sku, wh).receive(9));
        mvc.perform(
                        post("/stockyard/reservations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sku\":\"S\",\"warehouse\":\"W\",\"qty\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(r.id()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void deduct_oversell_is_409() throws Exception {
        when(engine.deduct(any(), any(), any(), any(), anyLong(), any()))
                .thenThrow(new OversellException("no stock"));
        mvc.perform(
                        post("/stockyard/deductions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"requestId\":\"D1\",\"sku\":\"S\",\"warehouse\":\"W\",\"qty\":5}"))
                .andExpect(status().isConflict());
    }

    @Test
    void position_ok() throws Exception {
        Sku sku = Sku.of("S");
        Warehouse wh = Warehouse.of("W");
        when(engine.position(any(), any()))
                .thenReturn(StockPosition.of(sku, wh).receive(10).allocate(3));
        mvc.perform(get("/stockyard/positions").param("sku", "S").param("warehouse", "W"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(10))
                .andExpect(jsonPath("$.available").value(7));
    }
}
