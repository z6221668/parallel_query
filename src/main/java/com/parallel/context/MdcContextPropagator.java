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

import org.slf4j.MDC;

import java.util.Map;

/**
 * Propagates SLF4J MDC across threads.
 *
 * <p>Captures the caller's MDC map, including values such as {@code traceId},
 * {@code spanId}, and {@code userId}; restores it on a query worker; and clears
 * it after the task completes.</p>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ContextPropagator
 */
public class MdcContextPropagator implements ContextPropagator {

    /**
     * Creates an MDC context propagator.
     */
    public MdcContextPropagator() {
    }

    /**
     * Captures the caller's MDC map.
     *
     * @return a copy of the MDC map, possibly {@code null}
     */
    @Override
    public Object capture() {
        return MDC.getCopyOfContextMap();
    }

    /**
     * Restores the MDC map on the worker thread.
     *
     * @param snapshot the MDC map captured on the caller
     */
    @Override
    @SuppressWarnings("unchecked")
    public void restore(Object snapshot) {
        if (snapshot instanceof Map<?, ?> map) {
            MDC.setContextMap((Map<String, String>) map);
        } else {
            MDC.clear();
        }
    }

    /**
     * Clears the current worker thread's MDC map.
     */
    @Override
    public void clear() {
        MDC.clear();
    }
}
