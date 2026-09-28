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

import com.parallel.exception.ParallelTimeoutException;
import com.parallel.exception.ParallelQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-local manager for parallel query scopes.
 *
 * <p>Maintains a scope stack on the calling thread:</p>
 * <ul>
 *   <li><b>Nested scopes:</b> the inner {@link com.parallel.annotation.ParallelScope}
 *       can exit without changing the outer scope.</li>
 *   <li><b>Deadlines:</b> remaining time is computed for each scope and passed
 *       to queries started inside it.</li>
 *   <li><b>Scope exit:</b> unconsumed futures are awaited when configured, and
 *       thread-local state is cleared.</li>
 * </ul>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ScopeState
 */
public class ParallelContext {

    private static final Logger log = LoggerFactory.getLogger(ParallelContext.class);

    /**
     * Prevents instantiation of this utility class.
     */
    private ParallelContext() {
    }

    /**
     * Runtime state for one parallel scope.
     */
    public static class ScopeState {
        private final long startNanos;
        private final long timeoutMs;
        private final boolean awaitAllOnExit;
        private final List<CompletableFuture<?>> futures = Collections.synchronizedList(new ArrayList<>());

        /**
         * Creates a scope state.
         *
         * @param timeoutMs      scope timeout in milliseconds
         * @param awaitAllOnExit whether to await background tasks on exit
         */
        public ScopeState(long timeoutMs, boolean awaitAllOnExit) {
            this.startNanos = System.nanoTime();
            this.timeoutMs = timeoutMs;
            this.awaitAllOnExit = awaitAllOnExit;
        }

        /**
         * Registers a future started in this scope.
         *
         * @param future the non-null async task future
         */
        public void registerFuture(CompletableFuture<?> future) {
            Objects.requireNonNull(future, "Future to register must not be null");
            this.futures.add(future);
        }

        /**
         * Returns all async futures registered in this scope.
         *
         * @return the thread-safe list of futures
         */
        public List<CompletableFuture<?>> getFutures() {
            return futures;
        }

        /**
         * Computes remaining scope time in milliseconds.
         *
         * @return remaining milliseconds, or zero when the deadline has passed
         */
        public long getRemainingTimeoutMs() {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            long remaining = timeoutMs - elapsed;
            return Math.max(0, remaining);
        }

        /**
         * Cancels all unfinished async tasks in this scope.
         */
        public void cancelAll() {
            for (CompletableFuture<?> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }

        /**
         * Returns the configured scope timeout.
         *
         * @return timeout in milliseconds
         */
        public long getTimeoutMs() {
            return timeoutMs;
        }

        /**
         * Returns whether scope exit awaits all queries.
         *
         * @return {@code true} if all queries are awaited on exit
         */
        public boolean isAwaitAllOnExit() {
            return awaitAllOnExit;
        }
    }

    /**
     * Scope stack isolated to the current calling thread.
     */
    private static final ThreadLocal<List<ScopeState>> SCOPE_STACK = new ThreadLocal<>();

    /**
     * Checks whether the current thread has an active parallel scope.
     *
     * @return {@code true} if a scope is active
     */
    public static boolean isActive() {
        List<ScopeState> stack = SCOPE_STACK.get();
        return stack != null && !stack.isEmpty();
    }

    /**
     * Returns the active scope at the top of the current thread's stack.
     *
     * @return the top scope, or {@code null} if no scope is active
     */
    public static ScopeState currentScope() {
        List<ScopeState> stack = SCOPE_STACK.get();
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.get(stack.size() - 1);
    }

    /**
     * Enters a new parallel scope by pushing it onto the stack.
     *
     * @param timeoutMs      timeout in milliseconds
     * @param awaitAllOnExit whether to await all tasks on exit
     * @return the newly created scope state
     */
    public static ScopeState enter(long timeoutMs, boolean awaitAllOnExit) {
        ScopeState state = new ScopeState(timeoutMs, awaitAllOnExit);
        List<ScopeState> stack = SCOPE_STACK.get();
        if (stack == null) {
            stack = new ArrayList<>();
            SCOPE_STACK.set(stack);
        }
        stack.add(state);
        return state;
    }

    /**
     * Exits the current scope without a primary business exception.
     */
    public static void exit() {
        exit(null);
    }

    /**
     * Exits the current scope by popping it from the stack.
     *
     * <p>When the business method has failed ({@code primaryError != null}), cancels
     * unfinished tasks without waiting so their failures cannot hide the primary error.<br>
     * On normal exit with {@code awaitAllOnExit=true}, awaits unfinished futures.<br>
     * After the outermost scope exits, removes the ThreadLocal reference.</p>
     *
     * @param primaryError the business method's error, or {@code null} on normal exit
     */
    public static void exit(Throwable primaryError) {
        List<ScopeState> stack = SCOPE_STACK.get();
        if (stack != null && !stack.isEmpty()) {
            ScopeState state = stack.remove(stack.size() - 1);
            try {
                if (primaryError != null) {
                    // A primary error has occurred; cancel unfinished background tasks.
                    log.debug("Primary method threw exception [{}], canceling all background tasks in current scope",
                            primaryError.getClass().getSimpleName());
                    state.cancelAll();
                } else if (state.isAwaitAllOnExit()) {
                    awaitScopeFutures(state);
                }
            } finally {
                if (stack.isEmpty()) {
                    SCOPE_STACK.remove();
                }
            }
        }
    }

    /**
     * Awaits all futures in the specified scope.
     *
     * @param state the scope state
     */
    private static void awaitScopeFutures(ScopeState state) {
        List<CompletableFuture<?>> futures = state.getFutures();
        if (futures.isEmpty()) {
            return;
        }

        CompletableFuture<?>[] snapshot = futures.toArray(new CompletableFuture[0]);
        CompletableFuture<Throwable> completion = new CompletableFuture<>();
        AtomicInteger unfinished = new AtomicInteger(snapshot.length);
        for (CompletableFuture<?> future : snapshot) {
            future.whenComplete((result, error) -> {
                if (error != null) {
                    completion.complete(unwrapCompletion(error));
                } else if (unfinished.decrementAndGet() == 0) {
                    completion.complete(null);
                }
            });
        }
        long remainingMs = state.getRemainingTimeoutMs();

        try {
            Throwable failure;
            if (remainingMs > 0) {
                failure = completion.get(remainingMs, TimeUnit.MILLISECONDS);
            } else if (!completion.isDone()) {
                throw new TimeoutException("Scope timeout already elapsed");
            } else {
                failure = completion.get(0, TimeUnit.MILLISECONDS);
            }
            if (failure != null) {
                state.cancelAll();
                throw propagateFailure(failure);
            }
        } catch (TimeoutException te) {
            if (completion.isDone()) {
                Throwable failure = completion.getNow(null);
                if (failure != null) {
                    state.cancelAll();
                    throw propagateFailure(failure);
                }
            }
            state.cancelAll();
            throw new ParallelTimeoutException("Parallel execution timed out after " + state.getTimeoutMs() + "ms", te, state.getTimeoutMs());
        } catch (ExecutionException ee) {
            state.cancelAll();
            throw propagateFailure(unwrapCompletion(ee));
        } catch (InterruptedException e) {
            state.cancelAll();
            Thread.currentThread().interrupt();
            throw new ParallelQueryException("Interrupted while awaiting parallel queries", e);
        }
    }

    private static Throwable unwrapCompletion(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RuntimeException propagateFailure(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new ParallelQueryException("Parallel query failed", cause);
    }
}
