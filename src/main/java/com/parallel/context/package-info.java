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

/**
 * ThreadLocal parallel execution context management and cross-thread propagation SPI.
 *
 * <ul>
 *   <li>{@link com.parallel.context.ParallelContext} - Stack-based ThreadLocal scope manager.</li>
 *   <li>{@link com.parallel.context.ContextPropagator} - SPI interface for capturing, restoring, and clearing cross-thread contexts.</li>
 *   <li>{@link com.parallel.context.MdcContextPropagator} - SLF4J MDC context propagator for distributed tracing.</li>
 *   <li>{@link com.parallel.context.RequestContextPropagator} - Spring Web RequestContext propagator.</li>
 * </ul>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 */
package com.parallel.context;
