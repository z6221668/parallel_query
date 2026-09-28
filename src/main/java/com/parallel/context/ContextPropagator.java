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

package com.parallel.context;

/**
 * SPI for propagating context across threads.
 *
 * <p>Thread-local values on the calling thread, such as MDC tracing IDs, Spring
 * Security context, or Web request context, are not inherited automatically by
 * virtual threads. An implementation captures a snapshot before task submission,
 * restores it on the worker, and clears it in a {@code finally} block.</p>
 *
 * <h2>Extension:</h2>
 * <p>Register an implementation as a Spring bean to include it in propagation:</p>
 * <pre>{@code
 * @Component
 * public class CustomSecurityPropagator implements ContextPropagator {
 *     @Override
 *     public Object capture() {
 *         return SecurityContextHolder.getContext();
 *     }
 *     @Override
 *     public void restore(Object snapshot) {
 *         if (snapshot instanceof SecurityContext ctx) {
 *             SecurityContextHolder.setContext(ctx);
 *         }
 *     }
 *     @Override
 *     public void clear() {
 *         SecurityContextHolder.clearContext();
 *     }
 * }
 * }</pre>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see MdcContextPropagator
 * @see RequestContextPropagator
 */
public interface ContextPropagator {

    /**
     * Captures a context snapshot on the submitting thread.
     *
     * @return the captured snapshot, possibly {@code null}
     */
    Object capture();

    /**
     * Restores context on the virtual-thread worker.
     *
     * @param snapshot the snapshot captured on the submitting thread
     */
    void restore(Object snapshot);

    /**
     * Clears context after worker execution; propagators are cleared in reverse order.
     */
    void clear();
}
