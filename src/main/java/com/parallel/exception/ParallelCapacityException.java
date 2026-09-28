/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.parallel.exception;

/** Raised when the instance has reached its configured parallel query capacity. */
public class ParallelCapacityException extends ParallelQueryException {
    private static final long serialVersionUID = 1L;

    public ParallelCapacityException(int limit) {
        super("Parallel query capacity exhausted (parallel.max-concurrent-queries=" + limit + ")");
    }
}
