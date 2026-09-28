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
 * Declares a scope for parallel queries.
 *
 * <p>Annotate a business method that aggregates independent queries, such as a
 * service method. A query runs asynchronously only when it declares
 * {@link ParallelQuery#nonNullResult()} and its return type can be proxied safely.
 * Other queries remain synchronous to preserve null and object-method semantics.</p>
 *
 * <h2>Execution model:</h2>
 * <ol>
 *   <li><b>Lazy proxy:</b> an eligible query immediately returns a proxy assignable
 *       to its declared return type.</li>
 *   <li><b>Data dependencies:</b> the first method call on a proxy, such as
 *       {@code user.getId()} or {@code list.size()}, waits for that query while
 *       independent queries continue in the background.</li>
 *   <li><b>Context propagation:</b> MDC tracing values and Web request context are
 *       made available on virtual threads and cleared after execution. Request
 *       attributes share their underlying object with the caller.</li>
 *   <li><b>Lifecycle:</b> scope exit waits for tasks by default. Failure or timeout
 *       requests cancellation; whether underlying I/O responds depends on its implementation.</li>
 * </ol>
 *
 * <h2>Example:</h2>
 * <pre>{@code
 * @Service
 * public class OrderDetailService {
 *
 *     @Autowired private OrderMapper orderMapper;
 *     @Autowired private UserMapper userMapper;
 *     @Autowired private GoodsMapper goodsMapper;
 *     @Autowired private LogisticsMapper logisticsMapper;
 *
 *     // Serial time: 120ms + 100ms + 110ms + 90ms = 420ms.
 *     // Each query method also needs @ParallelQuery(nonNullResult = true).
 *     @ParallelScope(timeoutMs = 3000)
 *     public OrderDetailVO getOrderDetail(Long orderId) {
 *         Order order = orderMapper.selectRequiredOrder(orderId);
 *         User user = userMapper.selectRequiredUser(1001L);
 *         List<String> goods = goodsMapper.selectGoodsNamesByOrderId(orderId);
 *         Logistics logistics = logisticsMapper.selectRequiredByOrderId(orderId);
 *         return new OrderDetailVO(order, user, goods, logistics);
 *     }
 * }
 * }</pre>
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 * @see ParallelQuery
 * @see com.parallel.aspect.ParallelScopeAspect
 * @see com.parallel.context.ParallelContext
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface ParallelScope {

    /**
     * Deadline for all parallel queries in this scope, in milliseconds.
     *
     * <p>The default {@code -1L} uses {@code parallel.default-timeout-ms} (5000 ms
     * by default). A timeout requests cancellation; configure I/O timeouts separately.</p>
     *
     * @return the scope timeout in milliseconds
     */
    long timeoutMs() default -1L;

    /**
     * Whether scope exit waits for all unconsumed queries to complete.
     *
     * <p>Defaults to {@code true}, keeping tasks within the method's lifecycle.
     * Disabling this option lets tasks and proxies outlive the scope method.</p>
     *
     * @return {@code true} to wait on exit; {@code false} to allow deferred access
     */
    boolean awaitAllOnExit() default true;
}
