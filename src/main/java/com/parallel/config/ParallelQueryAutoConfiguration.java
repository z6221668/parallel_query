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

import com.parallel.aspect.ParallelQueryAspect;
import com.parallel.aspect.ParallelScopeAspect;
import com.parallel.aspect.InterfaceParallelQueryAdvisor;
import com.parallel.context.ContextPropagator;
import com.parallel.context.MdcContextPropagator;
import com.parallel.context.RequestContextPropagator;
import com.parallel.executor.ParallelExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Spring Boot auto-configuration for parallel queries.
 *
 * <p>Registers components conditionally when the application starts:</p>
 * <ul>
 *   <li>Uses Spring Boot AOP configuration and the host application's proxy settings.</li>
 *   <li>Allows core components to be replaced through {@code @ConditionalOnMissingBean}.</li>
 *   <li>Collects all registered {@link ContextPropagator} implementations.</li>
 * </ul>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ParallelProperties
 * @see ParallelExecutor
 * @see ParallelScopeAspect
 * @see ParallelQueryAspect
 */
@AutoConfiguration
@EnableConfigurationProperties(ParallelProperties.class)
@ConditionalOnProperty(prefix = "parallel", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ParallelQueryAutoConfiguration {

    /**
     * Creates the auto-configuration instance.
     */
    public ParallelQueryAutoConfiguration() {
    }

    /**
     * Registers the SLF4J MDC context propagator.
     *
     * @return the MDC context propagator
     */
    @Bean
    @ConditionalOnMissingBean(MdcContextPropagator.class)
    @ConditionalOnProperty(prefix = "parallel", name = "propagate-mdc", havingValue = "true", matchIfMissing = true)
    public MdcContextPropagator mdcContextPropagator() {
        return new MdcContextPropagator();
    }

    /**
     * Registers the Spring Web request context propagator.
     *
     * @return the request context propagator
     */
    @Bean
    @ConditionalOnMissingBean(RequestContextPropagator.class)
    @ConditionalOnProperty(prefix = "parallel", name = "propagate-request-context", havingValue = "true", matchIfMissing = true)
    public RequestContextPropagator requestContextPropagator() {
        return new RequestContextPropagator();
    }

    /**
     * Registers the Java 21 virtual-thread query executor.
     *
     * @param properties  global configuration properties
     * @param propagators all context propagators in the application context
     * @return the parallel executor bean
     */
    @Bean
    @ConditionalOnMissingBean
    public ParallelExecutor parallelExecutor(ParallelProperties properties, List<ContextPropagator> propagators) {
        return new ParallelExecutor(properties, propagators);
    }

    /**
     * Registers the parallel scope aspect.
     *
     * @return the parallel scope aspect
     */
    @Bean
    @ConditionalOnMissingBean
    public ParallelScopeAspect parallelScopeAspect(ParallelProperties properties) {
        return new ParallelScopeAspect(properties);
    }

    /**
     * Registers the parallel query aspect.
     *
     * @param parallelExecutor the query executor
     * @param properties       configuration properties
     * @return the parallel query aspect
     */
    @Bean
    @ConditionalOnMissingBean
    public ParallelQueryAspect parallelQueryAspect(ParallelExecutor parallelExecutor, ParallelProperties properties) {
        return new ParallelQueryAspect(parallelExecutor, properties);
    }

    @Bean
    @ConditionalOnMissingBean(InterfaceParallelQueryAdvisor.class)
    public InterfaceParallelQueryAdvisor interfaceParallelQueryAdvisor(ParallelQueryAspect aspect) {
        return new InterfaceParallelQueryAdvisor(aspect);
    }
}
