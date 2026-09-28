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

package com.parallel.proxy;

import com.parallel.exception.ParallelQueryException;
import com.parallel.exception.ParallelTimeoutException;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.matcher.ElementMatchers;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Factory for lazy query-result proxies.
 *
 * <p>For an async query, this factory immediately returns a proxy on the caller.
 * Passing or assigning the proxy does not wait for the result. The first call to
 * an intercepted instance method, such as {@code user.getId()} or
 * {@code list.size()}, waits for the underlying future and delegates to the real object.</p>
 *
 * <h2>Proxy strategies:</h2>
 * <table border="1">
 *   <caption>Proxy strategy by return type</caption>
 *   <tr><th>Return type</th><th>Strategy</th><th>Behavior</th></tr>
 *   <tr><td>Interface</td><td>JDK dynamic proxy ({@link java.lang.reflect.Proxy})</td><td>No generated subclass</td></tr>
 *   <tr><td>Ordinary class or POJO</td><td>ByteBuddy subclass and Objenesis</td><td>No default constructor required</td></tr>
 *   <tr><td>Final class</td><td>Eager evaluation</td><td>Cannot be subclassed; runs synchronously</td></tr>
 *   <tr><td>Primitive or void</td><td>Eager evaluation</td><td>Cannot be proxied; runs synchronously</td></tr>
 * </table>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 */
public class LazyProxyFactory {

    private static final Logger log = LoggerFactory.getLogger(LazyProxyFactory.class);

    /**
     * Prevents instantiation of this utility class.
     */
    private LazyProxyFactory() {
    }

    private static final Objenesis OBJENESIS = new ObjenesisStd(true);
    private static final Map<Class<?>, Class<?>> PROXY_CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * Internal interface implemented by generated proxies to expose the real result.
     */
    public interface LazyWrapper {
        /**
         * Returns the underlying business object.
         *
         * @return the real result object
         */
        Object $unwrap();
    }

    /**
     * Interceptor holding the future, deadline, and thread-safe result cache.
     */
    public static class LazyInterceptor {
        private final CompletableFuture<?> future;
        private final long timeoutMs;
        private final long startNanos;
        private final long timeoutNanos;
        private volatile Object cachedTarget;
        private volatile boolean resolved = false;

        /**
         * Creates a lazy interceptor.
         *
         * @param future    async result future
         * @param timeoutMs timeout in milliseconds
         */
        public LazyInterceptor(CompletableFuture<?> future, long timeoutMs) {
            this.future = Objects.requireNonNull(future, "CompletableFuture must not be null");
            this.timeoutMs = timeoutMs;
            this.startNanos = System.nanoTime();
            this.timeoutNanos = timeoutMs > 0 ? TimeUnit.MILLISECONDS.toNanos(timeoutMs) : 0;
        }

        /**
         * Waits for the real result and caches it with double-checked locking.
         *
         * @return the real result object
         * @throws ParallelTimeoutException if the deadline expires
         * @throws RuntimeException         if the query fails
         */
        public Object getRealTarget() {
            if (resolved) {
                return cachedTarget;
            }
            synchronized (this) {
                if (resolved) {
                    return cachedTarget;
                }
                try {
                    Object target;
                    if (timeoutMs > 0) {
                        long remainingNanos = Math.max(0, timeoutNanos - (System.nanoTime() - startNanos));
                        target = future.get(remainingNanos, TimeUnit.NANOSECONDS);
                    } else {
                        target = future.get();
                    }
                    this.cachedTarget = target;
                    this.resolved = true;
                    return target;
                } catch (TimeoutException te) {
                    future.cancel(true);
                    throw new ParallelTimeoutException("Parallel query execution timed out after " + timeoutMs + "ms", te, timeoutMs);
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    if (cause instanceof Error err) {
                        throw err;
                    }
                    throw new ParallelQueryException("Parallel query execution failed: " + cause.getMessage(), cause);
                } catch (CompletionException ce) {
                    Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    if (cause instanceof Error err) {
                        throw err;
                    }
                    throw new ParallelQueryException("Parallel query execution failed: " + cause.getMessage(), cause);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ParallelQueryException("Parallel query execution interrupted", ie);
                } catch (Exception e) {
                    throw new ParallelQueryException("Parallel query execution encountered unexpected error", e);
                }
            }
        }

        /**
         * Intercepts a proxy method and delegates to the real object.
         *
         * @param method invoked method
         * @param args   method arguments
         * @return the invocation result
         * @throws Throwable a business error
         */
        @RuntimeType
        public Object intercept(@Origin Method method, @AllArguments Object[] args) throws Throwable {
            if ("$unwrap".equals(method.getName()) && (args == null || args.length == 0)) {
                return getRealTarget();
            }

            Object realTarget = getRealTarget();
            if (realTarget == null) {
                throw new ParallelQueryException("Query declared nonNullResult=true returned null");
            }

            // Unwrap equals arguments and delegate toString/hashCode transparently.
            if ("equals".equals(method.getName()) && args != null && args.length == 1) {
                Object other = unwrap(args[0]);
                return realTarget.equals(other);
            }
            if ("hashCode".equals(method.getName()) && (args == null || args.length == 0)) {
                return realTarget.hashCode();
            }
            if ("toString".equals(method.getName()) && (args == null || args.length == 0)) {
                return realTarget.toString();
            }

            try {
                if (!method.canAccess(realTarget)) {
                    method.trySetAccessible();
                }
                return method.invoke(realTarget, args);
            } catch (InvocationTargetException ite) {
                throw ite.getTargetException();
            }
        }
    }

    /**
     * Creates a lazy proxy for a return type and async future.
     *
     * @param returnType non-null query return type
     * @param future     non-null async result future
     * @param timeoutMs  timeout in milliseconds
     * @param <T>        result type
     * @return a lazy proxy or synchronously resolved value
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> returnType, CompletableFuture<?> future, long timeoutMs) {
        Objects.requireNonNull(returnType, "ReturnType must not be null");
        Objects.requireNonNull(future, "CompletableFuture must not be null");

        if (returnType == void.class || returnType == Void.class) {
            return null;
        }

        // Resolve primitive results synchronously.
        if (returnType.isPrimitive()) {
            try {
                return (T) future.get(timeoutMs > 0 ? timeoutMs : 5000L, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new ParallelQueryException("Failed to evaluate primitive result synchronously", e);
            }
        }

        LazyInterceptor interceptor = new LazyInterceptor(future, timeoutMs);
        ClassLoader classLoader = resolveClassLoader(returnType);

        if (!canProxy(returnType)) {
            log.debug("Target class [{}] cannot be safely proxied, evaluating synchronously.", returnType.getName());
            return (T) interceptor.getRealTarget();
        }

        // 1. Use a JDK dynamic proxy for interfaces.
        if (returnType.isInterface()) {
            return (T) Proxy.newProxyInstance(
                    classLoader,
                    new Class<?>[]{returnType, LazyWrapper.class},
                    (proxy, method, args) -> {
                        if ("$unwrap".equals(method.getName()) && (args == null || args.length == 0)) {
                            return interceptor.getRealTarget();
                        }
                        Object realTarget = interceptor.getRealTarget();
                        if (realTarget == null) {
                            throw new ParallelQueryException("Query declared nonNullResult=true returned null");
                        }
                        if ("equals".equals(method.getName()) && args != null && args.length == 1) {
                            return realTarget.equals(unwrap(args[0]));
                        }
                        if ("hashCode".equals(method.getName()) && (args == null || args.length == 0)) {
                            return realTarget.hashCode();
                        }
                        if ("toString".equals(method.getName()) && (args == null || args.length == 0)) {
                            return realTarget.toString();
                        }
                        try {
                            if (!method.canAccess(realTarget)) {
                                method.trySetAccessible();
                            }
                            return method.invoke(realTarget, args);
                        } catch (InvocationTargetException ite) {
                            throw ite.getTargetException();
                        }
                    }
            );
        }

        // 3. Use a ByteBuddy subclass and Objenesis for ordinary classes.
        try {
            Class<?> proxyClass = PROXY_CLASS_CACHE.computeIfAbsent(returnType, cls ->
                    new ByteBuddy()
                            .subclass(cls, net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy.Default.NO_CONSTRUCTORS)
                            // Keep implementation state out of field-based serializers such as Jackson.
                            .defineField("$interceptor", LazyInterceptor.class, Modifier.PRIVATE)
                            .implement(LazyWrapper.class)
                            .method(ElementMatchers.isMethod()
                                    .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class))
                                            .or(ElementMatchers.isEquals())
                                            .or(ElementMatchers.isHashCode())
                                            .or(ElementMatchers.isToString())))
                            .intercept(MethodDelegation.toField("$interceptor"))
                            .make()
                            .load(classLoader, ClassLoadingStrategy.Default.WRAPPER)
                            .getLoaded()
            );

            Field interceptorField = FIELD_CACHE.computeIfAbsent(proxyClass, pCls -> {
                try {
                    Field f = pCls.getDeclaredField("$interceptor");
                    f.trySetAccessible();
                    return f;
                } catch (NoSuchFieldException e) {
                    throw new ParallelQueryException("Failed to locate $interceptor field in ByteBuddy proxy class", e);
                }
            });

            Object instance = OBJENESIS.newInstance(proxyClass);
            interceptorField.set(instance, interceptor);
            return (T) instance;
        } catch (Throwable t) {
            log.error("ByteBuddy proxy creation failed for [{}], falling back to eager evaluation. Cause: {}",
                    returnType.getName(), t.getMessage(), t);
            return (T) interceptor.getRealTarget();
        }
    }

    /** Checks whether a lazy proxy can intercept every instance method. */
    public static boolean canProxy(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.isSealed() || Modifier.isFinal(type.getModifiers())) {
            return false;
        }
        if (type.isInterface()) {
            return true;
        }
        if (type.getName().startsWith("java.") || type.getName().startsWith("javax.")) {
            return false;
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            // WRAPPER defines the subclass in another loader, where package-private types are inaccessible.
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())) {
                    continue;
                }
                int modifiers = method.getModifiers();
                // WRAPPER uses another class loader, so package-private methods cannot be overridden.
                if (Modifier.isFinal(modifiers)
                        || (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Unwraps a proxy recursively to obtain its real business object.
     *
     * @param object a proxy or an ordinary object
     * @return the real business object
     */
    public static Object unwrap(Object object) {
        Object current = object;
        while (current instanceof LazyWrapper wrapper) {
            current = wrapper.$unwrap();
        }
        return current;
    }

    /**
     * Resolves a suitable class loader.
     */
    private static ClassLoader resolveClassLoader(Class<?> targetType) {
        ClassLoader cl = targetType.getClassLoader();
        if (cl != null) {
            return cl;
        }
        cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            return cl;
        }
        return LazyProxyFactory.class.getClassLoader();
    }
}
