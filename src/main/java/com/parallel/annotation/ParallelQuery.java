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

package com.parallel.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly marks a query eligible for parallel execution.
 *
 * <p>Apply this annotation to a query type or an individual method:</p>
 * <ul>
 *   <li><b>Type level:</b> annotate a class or interface, such as
 *       {@code @ParallelQuery public interface RemoteClient}. Eligible read-only methods
 *       can then participate in {@link ParallelScope} scheduling.</li>
 *   <li><b>Method level:</b> enable a specific method and optionally set its timeout.</li>
 * </ul>
 *
 * <p>A dedicated Advisor recognizes annotations on interfaces. Unannotated methods
 * remain synchronous. Remote clients may also be annotated at the type or method level.</p>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ParallelScope
 * @see com.parallel.aspect.ParallelQueryAspect
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface ParallelQuery {

    /**
     * Timeout for this query method, in milliseconds.
     *
     * <p>The default {@code -1L} uses the remaining time in the enclosing
     * {@link ParallelScope#timeoutMs()} deadline or the configured default.</p>
     *
     * @return the method timeout in milliseconds
     */
    long timeoutMs() default -1L;

    /**
     * Declares that the result is never {@code null}, allowing a lazy proxy to be
     * returned immediately. An incorrect declaration breaks null-check semantics.
     * Without this declaration, the query runs synchronously so {@code result == null}
     * retains its original meaning.
     */
    boolean nonNullResult() default false;
}
