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

import com.parallel.annotation.ParallelQuery;
import com.parallel.config.ParallelProperties;
import com.parallel.context.ParallelContext;
import com.parallel.exception.ParallelQueryException;
import com.parallel.exception.ParallelCapacityException;
import com.parallel.exception.ParallelTimeoutException;
import com.parallel.executor.ParallelExecutor;
import com.parallel.proxy.LazyProxyFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Aspect that intercepts eligible parallel queries.
 *
 * <p>Within an active {@link com.parallel.annotation.ParallelScope}, eligible
 * queries with non-null results and safely proxyable return types run
 * asynchronously. Other candidate queries remain synchronous.</p>
 *
 * <h2>Execution safeguards:</h2>
 * <ul>
 *   <li><b>Known write methods:</b> names starting with {@code insert},
 *       {@code update}, {@code delete}, {@code save}, and similar prefixes remain
 *       synchronous. Users must ensure other annotated methods are read-only.</li>
 *   <li><b>Non-null contract:</b> only queries declaring
 *       {@code @ParallelQuery(nonNullResult=true)} run asynchronously.</li>
 *   <li><b>Unproxyable results:</b> primitives, nullable results, and classes
 *       with final instance methods run synchronously.</li>
 * </ul>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ParallelQuery
 * @see ParallelExecutor
 * @see LazyProxyFactory
 */
@Aspect
@Order(200)
public class ParallelQueryAspect {

    private static final Logger log = LoggerFactory.getLogger(ParallelQueryAspect.class);

    /**
     * Common write-method prefixes; other names do not prove a method is read-only.
     */
    private static final Set<String> WRITE_PREFIXES = Set.of(
            "insert", "update", "delete", "save", "remove", "add", "batch", "modify", "del", "create", "alter", "drop"
    );

    private final ParallelExecutor parallelExecutor;
    private final ParallelProperties properties;

    /**
     * Creates the parallel query aspect.
     *
     * @param parallelExecutor non-null query executor
     * @param properties       configuration properties, or {@code null} for defaults
     */
    public ParallelQueryAspect(ParallelExecutor parallelExecutor, ParallelProperties properties) {
        this.parallelExecutor = Objects.requireNonNull(parallelExecutor, "ParallelExecutor must not be null");
        this.properties = properties != null ? properties : new ParallelProperties();
    }

    /**
     * Matches methods or types explicitly annotated with {@link ParallelQuery}.
     */
    @Pointcut("@annotation(com.parallel.annotation.ParallelQuery) || @within(com.parallel.annotation.ParallelQuery) || execution(* (@com.parallel.annotation.ParallelQuery *).*(..))")
    public void annotatedQueryPointcut() {}

    /**
     * Intercepts a candidate query call.
     *
     * @param joinPoint the intercepted call
     * @return a lazy proxy or the actual result
     * @throws Throwable an execution error
     */
    @Around("annotatedQueryPointcut()")
    public Object aroundQuery(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Class<?> targetClass = AopUtils.getTargetClass(joinPoint.getTarget());
        return invokeQuery(method, targetClass, joinPoint::proceed);
    }

    @FunctionalInterface
    public interface QueryInvocation {
        Object proceed() throws Throwable;
    }

    /** Shared dispatch path for the interface Advisor and this aspect. */
    public Object invokeQuery(Method method, Class<?> targetClass, QueryInvocation invocation) throws Throwable {
        // 1. Outside @ParallelScope, execute synchronously without dispatch overhead.
        if (!ParallelContext.isActive()) {
            return invocation.proceed();
        }

        String methodName = method.getName().toLowerCase();
        Class<?> returnType = method.getReturnType();

        // 2. Find @ParallelQuery on interfaces, superclasses, and proxied methods.
        ParallelQuery parallelQuery = findParallelQuery(method, targetClass);

        // Preserve the original call when no annotation is present.
        if (parallelQuery == null) {
            return invocation.proceed();
        }

        // 4. Keep recognized write methods on the calling thread.
        if (isWriteMethod(methodName)) {
            log.debug("Skipping parallel execution for mutation/write method [{}] to protect data integrity.",
                    method.getName());
            return invocation.proceed();
        }

        // Transactions and persistence contexts are thread-bound; keep calls synchronous.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return invocation.proceed();
        }

        // 6. Void and primitive return types require synchronous execution.
        if (returnType == void.class || returnType == Void.class || returnType.isPrimitive()
                || !parallelQuery.nonNullResult()
                || !LazyProxyFactory.canProxy(returnType)) {
            log.debug("Method [{}] is not eligible for lazy proxy (returnType={}), executing synchronously.",
                    method.getName(), returnType.getSimpleName());
            return invocation.proceed();
        }

        // 7. Resolve the timeout from the method, scope deadline, and global default.
        ParallelContext.ScopeState scopeState = ParallelContext.currentScope();
        long timeoutMs = properties.getDefaultTimeoutMs();
        if (scopeState != null) {
            timeoutMs = scopeState.getRemainingTimeoutMs();
        }
        if (parallelQuery != null && parallelQuery.timeoutMs() > 0) {
            timeoutMs = Math.min(timeoutMs, parallelQuery.timeoutMs());
        }
        if (timeoutMs <= 0) {
            throw new ParallelTimeoutException(scopeState != null ? scopeState.getTimeoutMs() : properties.getDefaultTimeoutMs());
        }

        if (log.isDebugEnabled()) {
            log.debug("Submitting parallel query for method [{}], returnType={}, timeoutMs={}",
                    method.getName(), returnType.getSimpleName(), timeoutMs);
        }

        // 8. Submit to the Java 21 virtual-thread executor.
        CompletableFuture<Object> future;
        try {
            future = parallelExecutor.submit(() -> {
                try {
                    Object result = invocation.proceed();
                    if (result == null) {
                        throw new ParallelQueryException("Query declared nonNullResult=true returned null: " + method.getName());
                    }
                    return result;
                } catch (Throwable t) {
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException(t);
                }
            });
        } catch (ParallelCapacityException capacityException) {
            if (!properties.isFallbackToCallerOnCapacity()) {
                throw capacityException;
            }
            log.debug("Parallel query capacity reached; running [{}] on caller thread", method.getName());
            return invocation.proceed();
        }

        // 9. Register the task for scope-exit waiting and timeout control.
        if (scopeState != null) {
            scopeState.registerFuture(future);
        }

        // 10. Return a lazy proxy immediately.
        return LazyProxyFactory.createProxy(returnType, future, timeoutMs);
    }

    private ParallelQuery findParallelQuery(Method method, Class<?> targetClass) {
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        ParallelQuery annotation = AnnotatedElementUtils.findMergedAnnotation(specificMethod, ParallelQuery.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(method, ParallelQuery.class);
        }
        if (annotation != null) {
            return annotation;
        }
        for (Class<?> ifc : ClassUtils.getAllInterfacesForClassAsSet(targetClass)) {
            Method interfaceMethod = ReflectionUtils.findMethod(ifc, method.getName(), method.getParameterTypes());
            if (interfaceMethod != null) {
                annotation = AnnotatedElementUtils.findMergedAnnotation(interfaceMethod, ParallelQuery.class);
                if (annotation != null) {
                    return annotation;
                }
            }
        }
        annotation = AnnotatedElementUtils.findMergedAnnotation(targetClass, ParallelQuery.class);
        if (annotation != null) {
            return annotation;
        }
        for (Class<?> ifc : ClassUtils.getAllInterfacesForClassAsSet(targetClass)) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(ifc, ParallelQuery.class);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * Checks whether a method name starts with a known write prefix.
     *
     * @param methodName the lowercase method name
     * @return {@code true} for a recognized write method
     */
    private boolean isWriteMethod(String methodName) {
        for (String prefix : WRITE_PREFIXES) {
            if (methodName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

}
