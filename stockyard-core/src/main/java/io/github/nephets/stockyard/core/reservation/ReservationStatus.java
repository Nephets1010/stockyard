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

/** 预占单状态机。 */
public enum ReservationStatus {
    /** 已创建，待生效。 */
    PENDING,
    /** 生效中，已占用库存。 */
    ACTIVE,
    /** 已确认，库存已扣减。 */
    CONFIRMED,
    /** 已取消，库存已释放。 */
    CANCELLED,
    /** 超时失效，库存已释放。 */
    EXPIRED
}
