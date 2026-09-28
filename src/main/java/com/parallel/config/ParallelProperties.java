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

package com.parallel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration properties for Parallel Query Starter.
 *
 * <p>Configure the {@code parallel.*} prefix in {@code application.yml} or
 * {@code application.properties}:</p>
 * <pre>{@code
 * parallel:
 *   enabled: true                     # Enable parallel queries
 *   default-timeout-ms: 5000          # Default scope timeout in milliseconds
 *   max-concurrent-queries: 64        # Maximum concurrent async queries per instance
 *   fallback-to-caller-on-capacity: true # Run on the caller when capacity is full
 *   shutdown-timeout-seconds: 10      # Shutdown wait time in seconds
 *   propagate-mdc: true               # Propagate SLF4J MDC
 *   propagate-request-context: true   # Propagate Web RequestContext
 * }</pre>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "parallel")
public class ParallelProperties {

    /**
     * Creates the configuration properties.
     */
    public ParallelProperties() {
    }

    /**
     * Whether parallel queries are enabled; defaults to {@code true}.
     */
    private boolean enabled = true;

    /**
     * Default scope timeout in milliseconds; defaults to 5000.
     */
    private long defaultTimeoutMs = 5000L;

    /** Maximum concurrent async queries per application instance. */
    private int maxConcurrentQueries = 64;

    /** Run on the caller when async capacity is full; otherwise reject the query. */
    private boolean fallbackToCallerOnCapacity = true;

    /**
     * Maximum wait during graceful Spring shutdown, in seconds; defaults to 10.
     */
    private int shutdownTimeoutSeconds = 10;

    /**
     * Whether to propagate SLF4J MDC across threads; defaults to {@code true}.
     */
    private boolean propagateMdc = true;

    /**
     * Whether to propagate Web RequestAttributes; defaults to {@code true}.
     */
    private boolean propagateRequestContext = true;

    /**
     * Returns whether parallel queries are enabled.
     *
     * @return {@code true} if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether parallel queries are enabled.
     *
     * @param enabled whether to enable parallel queries
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the default scope timeout in milliseconds.
     *
     * @return timeout in milliseconds
     */
    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    /**
     * Sets the default scope timeout in milliseconds.
     *
     * @param defaultTimeoutMs timeout in milliseconds
     */
    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        if (defaultTimeoutMs <= 0) {
            throw new IllegalArgumentException("parallel.default-timeout-ms must be positive");
        }
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public int getMaxConcurrentQueries() {
        return maxConcurrentQueries;
    }

    public void setMaxConcurrentQueries(int maxConcurrentQueries) {
        if (maxConcurrentQueries <= 0) {
            throw new IllegalArgumentException("parallel.max-concurrent-queries must be positive");
        }
        this.maxConcurrentQueries = maxConcurrentQueries;
    }

    public boolean isFallbackToCallerOnCapacity() {
        return fallbackToCallerOnCapacity;
    }

    public void setFallbackToCallerOnCapacity(boolean fallbackToCallerOnCapacity) {
        this.fallbackToCallerOnCapacity = fallbackToCallerOnCapacity;
    }

    /**
     * Returns the maximum graceful shutdown wait in seconds.
     *
     * @return wait time in seconds
     */
    public int getShutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    /**
     * Sets the maximum graceful shutdown wait in seconds.
     *
     * @param shutdownTimeoutSeconds wait time in seconds
     */
    public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
        if (shutdownTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("parallel.shutdown-timeout-seconds must be positive");
        }
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    /**
     * Returns whether MDC propagation is enabled.
     *
     * @return {@code true} if enabled
     */
    public boolean isPropagateMdc() {
        return propagateMdc;
    }

    /**
     * Sets whether MDC propagation is enabled.
     *
     * @param propagateMdc whether to propagate MDC
     */
    public void setPropagateMdc(boolean propagateMdc) {
        this.propagateMdc = propagateMdc;
    }

    /**
     * Returns whether RequestContext propagation is enabled.
     *
     * @return {@code true} if enabled
     */
    public boolean isPropagateRequestContext() {
        return propagateRequestContext;
    }

    /**
     * Sets whether RequestContext propagation is enabled.
     *
     * @param propagateRequestContext whether to propagate RequestContext
     */
    public void setPropagateRequestContext(boolean propagateRequestContext) {
        this.propagateRequestContext = propagateRequestContext;
    }
}
