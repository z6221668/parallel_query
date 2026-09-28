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
 * Reports a parallel query timeout.
 *
 * <p>Thrown when either of these waits exceeds its deadline:</p>
 * <ul>
 *   <li>Scope exit awaits all tasks with {@code @ParallelScope(awaitAllOnExit=true)}.</li>
 *   <li>The caller accesses a lazy proxy before its query has completed.</li>
 * </ul>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ParallelQueryException
 */
public class ParallelTimeoutException extends ParallelQueryException {

    private static final long serialVersionUID = 1L;

    /**
     * Timeout threshold in milliseconds.
     */
    private final long timeoutMs;

    /**
     * Creates a timeout exception.
     *
     * @param timeoutMs configured timeout in milliseconds
     */
    public ParallelTimeoutException(long timeoutMs) {
        super("Parallel query execution timed out after " + timeoutMs + "ms");
        this.timeoutMs = timeoutMs;
    }

    /**
     * Creates a timeout exception with a message.
     *
     * @param message   error description
     * @param timeoutMs configured timeout in milliseconds
     */
    public ParallelTimeoutException(String message, long timeoutMs) {
        super(message);
        this.timeoutMs = timeoutMs;
    }

    /**
     * Creates a timeout exception with a message and cause.
     *
     * @param message   error description
     * @param cause     underlying error, such as {@link java.util.concurrent.TimeoutException}
     * @param timeoutMs configured timeout in milliseconds
     */
    public ParallelTimeoutException(String message, Throwable cause, long timeoutMs) {
        super(message, cause);
        this.timeoutMs = timeoutMs;
    }

    /**
     * Returns the timeout threshold in milliseconds.
     *
     * @return timeout in milliseconds
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }
}
