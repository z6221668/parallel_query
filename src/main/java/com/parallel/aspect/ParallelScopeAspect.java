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

package com.parallel.aspect;

import com.parallel.annotation.ParallelScope;
import com.parallel.config.ParallelProperties;
import com.parallel.context.ParallelContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

import java.util.Objects;

/**
 * Aspect that manages parallel query scopes.
 *
 * <p>An AspectJ around advice intercepts methods annotated with
 * {@link ParallelScope} and manages the calling thread's scope state:</p>
 * <ol>
 *   <li><b>Entry:</b> {@link ParallelContext#enter(long, boolean)} pushes a new
 *       {@link com.parallel.context.ParallelContext.ScopeState}.</li>
 *   <li><b>Queries:</b> {@link ParallelQueryAspect} intercepts eligible calls and
 *       registers their futures in this scope.</li>
 *   <li><b>Exit:</b> {@link ParallelContext#exit()} pops the scope in a
 *       {@code finally} block. With {@code awaitAllOnExit=true}, it awaits
 *       unconsumed futures and clears thread-local state.</li>
 * </ol>
 *
 * <p>This class does not declare {@code @Component}. The auto-configuration in
 * {@link com.parallel.config.ParallelQueryAutoConfiguration} registers it with
 * {@code @ConditionalOnMissingBean}, allowing applications to replace it.</p>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ParallelScope
 * @see ParallelContext
 * @see ParallelQueryAspect
 */
@Aspect
@Order(100)
public class ParallelScopeAspect {

    private static final Logger log = LoggerFactory.getLogger(ParallelScopeAspect.class);

    /**
     * Creates the scope aspect.
     */
    private final ParallelProperties properties;

    public ParallelScopeAspect(ParallelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "ParallelProperties must not be null");
    }

    /**
     * Intercepts a method annotated with {@link ParallelScope}.
     *
     * @param joinPoint     the intercepted call
     * @param parallelScope the scope annotation
     * @return the business method's result
     * @throws Throwable any business or timeout error
     */
    @Around("@annotation(parallelScope)")
    public Object aroundScope(ProceedingJoinPoint joinPoint, ParallelScope parallelScope) throws Throwable {
        Objects.requireNonNull(joinPoint, "ProceedingJoinPoint must not be null");
        Objects.requireNonNull(parallelScope, "ParallelScope annotation metadata must not be null");

        long timeoutMs = parallelScope.timeoutMs() > 0 ? parallelScope.timeoutMs() : properties.getDefaultTimeoutMs();
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Parallel scope timeout must be positive");
        }
        boolean awaitAllOnExit = parallelScope.awaitAllOnExit();

        if (log.isDebugEnabled()) {
            log.debug("Entering @ParallelScope on [{}], timeoutMs={}, awaitAllOnExit={}",
                    joinPoint.getSignature().toShortString(), timeoutMs, awaitAllOnExit);
        }

        ParallelContext.enter(timeoutMs, awaitAllOnExit);
        Throwable primaryError = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            primaryError = t;
            throw t;
        } finally {
            ParallelContext.exit(primaryError);
            if (log.isDebugEnabled()) {
                log.debug("Exited @ParallelScope on [{}]", joinPoint.getSignature().toShortString());
            }
        }
    }
}
