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

import com.parallel.exception.ParallelQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Propagates Spring Web request context across threads.
 *
 * <p>In a Servlet application, carries the caller's {@code RequestAttributes}
 * to async virtual threads. Reflection checks whether Spring Web is available;
 * this propagator does nothing when {@code spring-web} is absent.</p>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ContextPropagator
 */
public class RequestContextPropagator implements ContextPropagator {

    private static final Logger log = LoggerFactory.getLogger(RequestContextPropagator.class);

    /**
     * Creates the request context propagator.
     */
    public RequestContextPropagator() {
    }

    private static final Method GET_REQUEST_ATTRIBUTES;
    private static final Method SET_REQUEST_ATTRIBUTES;
    private static final Method RESET_REQUEST_ATTRIBUTES;
    private static final boolean AVAILABLE;

    static {
        Method getMethod = null;
        Method setMethod = null;
        Method resetMethod = null;
        boolean available = false;
        try {
            Class<?> holderClass = Class.forName("org.springframework.web.context.request.RequestContextHolder");
            Class<?> attributesClass = Class.forName("org.springframework.web.context.request.RequestAttributes");
            getMethod = holderClass.getMethod("getRequestAttributes");
            setMethod = holderClass.getMethod("setRequestAttributes", attributesClass, boolean.class);
            resetMethod = holderClass.getMethod("resetRequestAttributes");
            available = true;
        } catch (Throwable t) {
            log.debug("RequestContextHolder is not present on classpath, skipping RequestContext propagation.");
        }
        GET_REQUEST_ATTRIBUTES = getMethod;
        SET_REQUEST_ATTRIBUTES = setMethod;
        RESET_REQUEST_ATTRIBUTES = resetMethod;
        AVAILABLE = available;
    }

    /**
     * Captures RequestAttributes from the calling thread.
     *
     * @return current request attributes, or {@code null} outside Web requests
     */
    @Override
    public Object capture() {
        if (!AVAILABLE) {
            return null;
        }
        try {
            return GET_REQUEST_ATTRIBUTES.invoke(null);
        } catch (Throwable t) {
            throw new ParallelQueryException("Failed to capture RequestAttributes", t);
        }
    }

    /**
     * Restores RequestAttributes on the worker thread.
     *
     * @param snapshot RequestAttributes captured on the caller
     */
    @Override
    public void restore(Object snapshot) {
        if (!AVAILABLE || snapshot == null) {
            return;
        }
        try {
            SET_REQUEST_ATTRIBUTES.invoke(null, snapshot, false);
        } catch (Throwable t) {
            throw new ParallelQueryException("Failed to restore RequestAttributes", t);
        }
    }

    /**
     * Clears RequestAttributes from the current thread.
     */
    @Override
    public void clear() {
        if (!AVAILABLE) {
            return;
        }
        try {
            RESET_REQUEST_ATTRIBUTES.invoke(null);
        } catch (Throwable t) {
            throw new ParallelQueryException("Failed to clear RequestAttributes", t);
        }
    }
}
