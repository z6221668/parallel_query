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

/**
 * Base runtime exception for Parallel Query Starter.
 *
 * <p>Framework errors extend this class so applications can handle and report
 * them consistently.</p>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ParallelTimeoutException
 */
public class ParallelQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception without a message.
     */
    public ParallelQueryException() {
        super();
    }

    /**
     * Creates an exception with a message.
     *
     * @param message error description
     */
    public ParallelQueryException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message error description
     * @param cause   underlying cause
     */
    public ParallelQueryException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates an exception with a cause.
     *
     * @param cause underlying cause
     */
    public ParallelQueryException(Throwable cause) {
        super(cause);
    }
}
