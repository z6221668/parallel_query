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

package com.parallel.executor;

import com.parallel.config.ParallelProperties;
import com.parallel.context.ContextPropagator;
import com.parallel.exception.ParallelCapacityException;
import com.parallel.exception.ParallelQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes parallel query tasks.
 *
 * <p>Uses Java 21 virtual threads and limits concurrent async queries per instance.</p>
 * <ul>
 *   <li><b>Virtual threads:</b> each admitted query runs on its own virtual thread;
 *       the query aspect chooses caller fallback or rejection when capacity is full.</li>
 *   <li><b>Context:</b> {@link ContextPropagator} snapshots are captured before
 *       submission, restored on the worker, and cleared in reverse order.</li>
 *   <li><b>Shutdown:</b> as a Spring {@link DisposableBean}, this executor waits
 *       for active tasks and requests cancellation after the shutdown timeout.</li>
 * </ul>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ContextPropagator
 * @see ParallelProperties
 */
public class ParallelExecutor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ParallelExecutor.class);

    private final ExecutorService executorService;
    private final ParallelProperties properties;
    private final List<ContextPropagator> propagators;
    private final Semaphore querySlots;
    private final int maxConcurrentQueries;

    /**
     * Creates an executor with configuration and context propagators.
     *
     * @param properties  configuration properties, or {@code null} for defaults
     * @param propagators context propagators, possibly {@code null}
     */
    public ParallelExecutor(ParallelProperties properties, List<ContextPropagator> propagators) {
        this.properties = properties != null ? properties : new ParallelProperties();
        this.propagators = propagators != null ? Collections.unmodifiableList(new ArrayList<>(propagators)) : Collections.emptyList();
        this.maxConcurrentQueries = this.properties.getMaxConcurrentQueries();
        this.querySlots = new Semaphore(maxConcurrentQueries, true);

        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        log.info("ParallelExecutor initialized with Java 21 Virtual Threads (maxConcurrentQueries={}).",
                maxConcurrentQueries);
    }

    /**
     * Creates an executor with defaults, useful outside a Spring context.
     */
    public ParallelExecutor() {
        this(new ParallelProperties(), Collections.emptyList());
    }

    /**
     * Submits an async query task.
     *
     * <p>Captures active context snapshots on the caller, restores them on the
     * virtual thread, runs the task, then clears them in reverse order.</p>
     *
     * @param task non-null query logic
     * @param <T>  result type
     * @return a future for the async result
     * @throws ParallelCapacityException when capacity is full; the query aspect
     *         may instead run the query on the caller, according to configuration
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "Callable task must not be null");

        if (!querySlots.tryAcquire()) {
            throw new ParallelCapacityException(maxConcurrentQueries);
        }

        // 1. Capture context on the submitting thread.
        final List<ContextSnapshotPair> snapshots;
        if (!propagators.isEmpty()) {
            snapshots = new ArrayList<>(propagators.size());
            for (ContextPropagator propagator : propagators) {
                try {
                    Object snapshot = propagator.capture();
                    snapshots.add(new ContextSnapshotPair(propagator, snapshot));
                } catch (Throwable t) {
                    querySlots.release();
                    throw new ParallelQueryException("Failed to capture context from propagator: "
                            + propagator.getClass().getName(), t);
                }
            }
        } else {
            snapshots = Collections.emptyList();
        }

        // 0 = not started, 1 = running, 2 = finished or canceled before start.
        // A canceled running task keeps its slot until it actually exits.
        AtomicInteger taskState = new AtomicInteger();
        AtomicBoolean slotReleased = new AtomicBoolean();
        Runnable releaseSlot = () -> {
            if (slotReleased.compareAndSet(false, true)) {
                querySlots.release();
            }
        };
        AtomicReference<Future<?>> runningTask = new AtomicReference<>();
        CompletableFuture<T> future = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) {
                    if (taskState.compareAndSet(0, 2)) {
                        releaseSlot.run();
                    }
                    Future<?> taskHandle = runningTask.get();
                    if (taskHandle != null) {
                        taskHandle.cancel(mayInterruptIfRunning);
                    }
                }
                return cancelled;
            }
        };

        // 2. Execute on a Java 21 virtual thread.
        Future<?> handle;
        try {
            handle = executorService.submit(() -> {
                if (!taskState.compareAndSet(0, 1)) {
                    return;
                }
                T result = null;
                Throwable failure = null;
                try {
                    for (ContextSnapshotPair pair : snapshots) {
                        pair.propagator.restore(pair.snapshot);
                    }
                    result = task.call();
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    // Clear every propagator even if restoration failed partway through.
                    for (int i = snapshots.size() - 1; i >= 0; i--) {
                        ContextSnapshotPair pair = snapshots.get(i);
                        try {
                            pair.propagator.clear();
                        } catch (Throwable t) {
                            if (failure == null) {
                                failure = new ParallelQueryException("Failed to clear context via propagator: "
                                        + pair.propagator.getClass().getName(), t);
                            } else if (failure != t) {
                                failure.addSuppressed(t);
                            }
                        }
                    }
                    taskState.set(2);
                    releaseSlot.run();
                }
                if (failure == null) {
                    future.complete(result);
                } else {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | Error e) {
            if (taskState.compareAndSet(0, 2)) {
                releaseSlot.run();
            }
            throw e;
        }
        runningTask.set(handle);
        if (future.isCancelled()) {
            handle.cancel(true);
        }

        return future;
    }

    /**
     * Waits for tasks during container shutdown and requests cancellation on timeout.
     */
    @Override
    public void destroy() {
        if (executorService != null && !executorService.isShutdown()) {
            int timeoutSeconds = properties.getShutdownTimeoutSeconds();
            log.info("Shutting down ParallelExecutor gracefully, waiting up to {}s for active tasks to complete...", timeoutSeconds);
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                    log.warn("ParallelExecutor did not terminate within {}s, forcing shutdownNow...", timeoutSeconds);
                    executorService.shutdownNow();
                } else {
                    log.info("ParallelExecutor shutdown successfully.");
                }
            } catch (InterruptedException e) {
                log.warn("ParallelExecutor shutdown interrupted, forcing shutdownNow...");
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Pairs a propagator with its captured snapshot.
     */
    private record ContextSnapshotPair(ContextPropagator propagator, Object snapshot) {}
}
