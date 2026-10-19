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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 示例应用启动类。 */
@SpringBootApplication
public class StockyardSampleApplication {

    private static final Logger log = LoggerFactory.getLogger(StockyardSampleApplication.class);

    public static void main(String[] args) {
        log.info("stockyard sample 启动中，args 数量={}", args.length);
        SpringApplication.run(StockyardSampleApplication.class, args);
        log.info("stockyard sample 已就绪，默认租户头=X-Tenant-Id");
    }
}
