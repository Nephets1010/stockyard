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

import java.util.Optional;

/** 幂等扣减记录的存储接口。 */
public interface DeductionStore {

    /** 按 requestId 查找已存在的扣减记录。 */
    Optional<DeductionRecord> find(String requestId);

    /** 保存一条扣减记录（同 requestId 覆盖）。 */
    void save(DeductionRecord record);
}
