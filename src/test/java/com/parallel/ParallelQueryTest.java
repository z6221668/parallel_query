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

package com.parallel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parallel.annotation.ParallelQuery;
import com.parallel.annotation.ParallelScope;
import com.parallel.aspect.ParallelScopeAspect;
import com.parallel.aspect.ParallelQueryAspect;
import com.parallel.config.ParallelProperties;
import com.parallel.context.ContextPropagator;
import com.parallel.context.ParallelContext;
import com.parallel.exception.ParallelQueryException;
import com.parallel.exception.ParallelCapacityException;
import com.parallel.exception.ParallelTimeoutException;
import com.parallel.executor.ParallelExecutor;
import com.parallel.proxy.LazyProxyFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression tests for parallel query behavior and edge cases.
 *
 * @author parallel-team
 * @version 1.0.0
 * @since 1.0.0
 */
@SpringBootTest(classes = ParallelTestApplication.class)
@Import({
    ParallelQueryTest.AggregationService.class,
    ParallelQueryTest.MockOrderMapper.class,
    ParallelQueryTest.MockUserMapper.class,
    ParallelQueryTest.MockGoodsMapper.class,
    ParallelQueryTest.MockLogisticsMapper.class,
    ParallelQueryTest.MockScoreMapper.class,
    ParallelQueryTest.MockContextAndMutationMapper.class,
    ParallelQueryTest.MockAdvancedMapper.class,
    ParallelQueryTest.InterfaceClientImpl.class,
    ParallelQueryTest.GateMapper.class,
    ParallelQueryTest.PlainMapper.class
})
public class ParallelQueryTest {

    public static class Order {
        private Long id;
        private Long userId;
        public Order() {}
        public Order(Long id, Long userId) { this.id = id; this.userId = userId; }
        public Long getId() { return id; }
        public Long getUserId() { return userId; }
    }

    public static class User {
        private Long id;
        private String name;
        public User() {}
        public User(Long id, String name) { this.id = id; this.name = name; }
        public Long getId() { return id; }
        public String getName() { return name; }
    }

    public static class Logistics {
        private String trackNumber;
        public Logistics() {}
        public Logistics(String trackNumber) { this.trackNumber = trackNumber; }
        public String getTrackNumber() { return trackNumber; }
    }

    public static class Score {
        private Integer points;
        public Score() {}
        public Score(Integer points) { this.points = points; }
        public Integer getPoints() { return points; }
    }

    public static class ContextCheckResult {
        private String traceId;
        private boolean isVirtualThread;
        public ContextCheckResult() {}
        public ContextCheckResult(String traceId, boolean isVirtualThread) {
            this.traceId = traceId;
            this.isVirtualThread = isVirtualThread;
        }
        public String getTraceId() { return traceId; }
        public boolean isVirtualThread() { return isVirtualThread; }
    }

    @Component
    @ParallelQuery(nonNullResult = true)
    public static class MockOrderMapper {
        public Order selectById(Long id) {
            sleep(120);
            return new Order(id, 1001L);
        }
    }

    @Component
    @ParallelQuery(nonNullResult = true)
    public static class MockUserMapper {
        public User selectById(Long id) {
            sleep(100);
            return new User(id, "张三");
        }
    }

    @Component
    @ParallelQuery(nonNullResult = true)
    public static class MockGoodsMapper {
        public List<String> selectGoodsNamesByOrderId(Long orderId) {
            sleep(110);
            return Arrays.asList("商品A", "商品B", "商品C");
        }
    }

    @Component
    @ParallelQuery(nonNullResult = true)
    public static class MockLogisticsMapper {
        public Logistics selectByOrderId(Long orderId) {
            sleep(90);
            return new Logistics("SF123456789");
        }
    }

    @Component
    @ParallelQuery(nonNullResult = true)
    public static class MockScoreMapper {
        public Score selectScoreByUserId(Long userId) {
            sleep(80);
            return new Score(99);
        }

        public Score selectSlowScore(long sleepMs) {
            sleep(sleepMs);
            return new Score(100);
        }

        public Score selectErrorScore() {
            throw new IllegalStateException("Database connection pool exhausted");
        }
    }

    /**
     * Mapper used to test write-method exclusion and MDC propagation.
     */
    @Component
    public static class MockContextAndMutationMapper {
        // Only explicitly annotated read methods enter the query aspect.
        @ParallelQuery(nonNullResult = true)
        public ContextCheckResult selectContextInfo() {
            sleep(50);
            return new ContextCheckResult(MDC.get("traceId"), Thread.currentThread().isVirtual());
        }

        // An update method must remain synchronous even when declared in a Mapper.
        public ContextCheckResult updateUserInfo(String name) {
            return new ContextCheckResult(name, Thread.currentThread().isVirtual());
        }

        public ContextCheckResult selectNullable() {
            return null;
        }
    }

    @Component
    public static class PlainMapper {
        public String selectValue() {
            return "plain";
        }
    }

    public static class PrivateConstructorEntity {
        private String title;
        private PrivateConstructorEntity() {}
        public static PrivateConstructorEntity of(String title) {
            PrivateConstructorEntity entity = new PrivateConstructorEntity();
            entity.title = title;
            return entity;
        }
        public String getTitle() { return title; }
    }

    public static class FinalGetterEntity {
        private final String value = "real";
        public final String getValue() { return value; }
    }

    public static class PackagePrivateEntity {
        private final String value = "real";
        String packageValue() { return value; }
    }

    static class NonPublicEntity {
        public String getValue() { return "real"; }
    }

    public sealed static class SealedEntity permits FinalSealedEntity {
        public String getValue() { return "real"; }
    }

    public static final class FinalSealedEntity extends SealedEntity {}

    public static class QueryGate {
        private final CountDownLatch started = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);

        void awaitRelease() {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("query gate timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("query gate interrupted", e);
            }
        }
    }

    @Component
    @ParallelQuery(nonNullResult = true)
    public static class GateMapper {
        private volatile QueryGate gate;

        public void setGate(QueryGate gate) {
            this.gate = gate;
        }

        public Order selectFirst() {
            gate.awaitRelease();
            return new Order(1L, 10L);
        }

        public Order selectSecond() {
            gate.awaitRelease();
            return new Order(2L, 20L);
        }
    }

    public interface IStatsService {
        long getTotalCount();
        double getAverageScore();
        boolean isPassed();
    }

    @ParallelQuery(nonNullResult = true)
    public interface InterfaceClient {
        ContextCheckResult queryContext();
    }

    @Component
    public static class InterfaceClientImpl implements InterfaceClient {
        @Override
        public ContextCheckResult queryContext() {
            return new ContextCheckResult("interface", Thread.currentThread().isVirtual());
        }
    }

    @Component
    @ParallelQuery(nonNullResult = true)
    public static class MockAdvancedMapper {
        public PrivateConstructorEntity selectPrivateEntity() {
            sleep(40);
            return PrivateConstructorEntity.of("PrivateConstructorSuccessful");
        }

        @ParallelQuery
        public IStatsService selectNullInterface() {
            sleep(40);
            return null; // Exercise primitive method calls on an interface proxy with a null result.
        }

        @ParallelQuery(nonNullResult = true)
        public IStatsService selectInvalidNonNull() {
            return null;
        }

        public String[] selectArrayData() {
            sleep(30);
            return new String[]{"elem1", "elem2"};
        }

        public PackagePrivateEntity selectPackagePrivateEntity() {
            return new PackagePrivateEntity();
        }
    }

    public static class OrderDetailVO {
        private final Order order;
        private final User user;
        private final List<String> goods;
        private final Logistics logistics;
        private Score score;

        public OrderDetailVO(Order order, User user, List<String> goods, Logistics logistics) {
            this.order = order;
            this.user = user;
            this.goods = goods;
            this.logistics = logistics;
        }

        public void setScore(Score score) { this.score = score; }
        public Order getOrder() { return order; }
        public User getUser() { return user; }
        public List<String> getGoods() { return goods; }
        public Logistics getLogistics() { return logistics; }
        public Score getScore() { return score; }
    }

    @Service
    public static class AggregationService {
        @Autowired private MockOrderMapper orderMapper;
        @Autowired private MockUserMapper userMapper;
        @Autowired private MockGoodsMapper goodsMapper;
        @Autowired private MockLogisticsMapper logisticsMapper;
        @Autowired private MockScoreMapper scoreMapper;
        @Autowired private MockContextAndMutationMapper contextMapper;
        @Autowired private InterfaceClient interfaceClient;
        @Autowired private GateMapper gateMapper;

        // Serial baseline.
        public OrderDetailVO getDetailSync(Long orderId) {
            Order order = orderMapper.selectById(orderId);
            User user = userMapper.selectById(1001L);
            List<String> goods = goodsMapper.selectGoodsNamesByOrderId(orderId);
            Logistics logistics = logisticsMapper.selectByOrderId(orderId);
            return new OrderDetailVO(order, user, goods, logistics);
        }

        // Parallel execution with the opt-in annotation.
        @ParallelScope
        public OrderDetailVO getDetailParallel(Long orderId) {
            Order order = orderMapper.selectById(orderId);
            User user = userMapper.selectById(1001L);
            List<String> goods = goodsMapper.selectGoodsNamesByOrderId(orderId);
            Logistics logistics = logisticsMapper.selectByOrderId(orderId);
            return new OrderDetailVO(order, user, goods, logistics);
        }

        // The score query depends on the user query's ID.
        @ParallelScope
        public OrderDetailVO getDetailWithDependency(Long orderId) {
            Order order = orderMapper.selectById(orderId);
            User user = userMapper.selectById(1001L);
            Score score = scoreMapper.selectScoreByUserId(user.getId());
            List<String> goods = goodsMapper.selectGoodsNamesByOrderId(orderId);
            Logistics logistics = logisticsMapper.selectByOrderId(orderId);

            OrderDetailVO vo = new OrderDetailVO(order, user, goods, logistics);
            vo.setScore(score);
            return vo;
        }

        // Exercise MDC propagation and synchronous write-method protection.
        @ParallelScope
        public ContextCheckResult[] testMdcAndMutationSafety() {
            // Async query.
            ContextCheckResult asyncRead = contextMapper.selectContextInfo();
            // Synchronous update.
            ContextCheckResult syncWrite = contextMapper.updateUserInfo("update-safe");
            return new ContextCheckResult[]{asyncRead, syncWrite};
        }

        @ParallelScope
        public ContextCheckResult testReadWithinTransaction() {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                return contextMapper.selectContextInfo();
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }

        @ParallelScope
        public ContextCheckResult testAnnotatedInterfaceClient() {
            return interfaceClient.queryContext();
        }

        @ParallelScope(timeoutMs = 10_000)
        public Order[] testConcurrentDispatch() {
            Order first = gateMapper.selectFirst();
            Order second = gateMapper.selectSecond();
            return new Order[]{first, second};
        }

        // Scope exit times out when awaitAllOnExit is true.
        @ParallelScope(timeoutMs = 150, awaitAllOnExit = true)
        public Score testScopeTimeoutException() {
            return scoreMapper.selectSlowScore(400);
        }

        // Proxy access times out when awaitAllOnExit is false.
        @ParallelScope(timeoutMs = 150, awaitAllOnExit = false)
        public Score testProxyDereferenceTimeout() {
            return scoreMapper.selectSlowScore(400);
        }

        // Business errors propagate to the caller.
        @ParallelScope
        public Score testBusinessExceptionPropagation() {
            return scoreMapper.selectErrorScore();
        }

        // A primary business error must not be hidden by a background timeout.
        @ParallelScope(timeoutMs = 100)
        public void testPrimaryBusinessException() {
            scoreMapper.selectSlowScore(400); // This slow task would time out after 100 ms.
            throw new IllegalArgumentException("Explicit validation failed");
        }

        @Autowired private MockAdvancedMapper advancedMapper;

        @ParallelScope
        public PrivateConstructorEntity testPrivateConstructor() {
            return advancedMapper.selectPrivateEntity();
        }

        @ParallelScope
        public IStatsService testNullInterface() {
            return advancedMapper.selectNullInterface();
        }

        @ParallelScope
        public IStatsService testInvalidNonNull() {
            return advancedMapper.selectInvalidNonNull();
        }

        @ParallelScope
        public ContextCheckResult testAutoMapperNullable() {
            return contextMapper.selectNullable();
        }

        @ParallelScope
        public String[] testArrayData() {
            return advancedMapper.selectArrayData();
        }

        @ParallelScope
        public PackagePrivateEntity testPackagePrivateEntity() {
            return advancedMapper.selectPackagePrivateEntity();
        }
    }

    @Autowired
    private AggregationService aggregationService;
    @Autowired
    private GateMapper gateMapper;

    @Autowired
    private PlainMapper plainMapper;

    @Test
    public void testSerialVsParallel() {
        Long orderId = 888L;

        OrderDetailVO syncResult = aggregationService.getDetailSync(orderId);
        Assertions.assertEquals("张三", syncResult.getUser().getName());
        Assertions.assertEquals(3, syncResult.getGoods().size());
        Assertions.assertEquals("SF123456789", syncResult.getLogistics().getTrackNumber());

        OrderDetailVO parallelResult = aggregationService.getDetailParallel(orderId);

        Assertions.assertEquals(888L, parallelResult.getOrder().getId());
        Assertions.assertEquals("张三", parallelResult.getUser().getName());
        Assertions.assertEquals(3, parallelResult.getGoods().size());
        Assertions.assertInstanceOf(LazyProxyFactory.LazyWrapper.class, parallelResult.getGoods());
        Assertions.assertEquals("商品A", parallelResult.getGoods().get(0));
        Assertions.assertEquals("SF123456789", parallelResult.getLogistics().getTrackNumber());

    }

    @Test
    public void testParallelResultSerializesLikePlainResult() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OrderDetailVO plain = aggregationService.getDetailSync(888L);
        OrderDetailVO parallel = aggregationService.getDetailParallel(888L);
        Assertions.assertEquals(mapper.readTree(mapper.writeValueAsString(plain)),
                mapper.readTree(mapper.writeValueAsString(parallel)));
        Assertions.assertFalse(mapper.writeValueAsString(parallel).contains("$interceptor"));
    }

    @Test
    public void testDependencyParallel() {
        Long orderId = 999L;
        OrderDetailVO result = aggregationService.getDetailWithDependency(orderId);

        Assertions.assertEquals(999L, result.getOrder().getId());
        Assertions.assertEquals(1001L, result.getUser().getId());
        Assertions.assertEquals(99, result.getScore().getPoints());
        Assertions.assertInstanceOf(LazyProxyFactory.LazyWrapper.class, result.getScore());
    }

    @Test
    public void testIndependentQueriesStartBeforeEitherCompletes() throws Exception {
        QueryGate gate = new QueryGate();
        gateMapper.setGate(gate);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Order[]> result = caller.submit(aggregationService::testConcurrentDispatch);
            Assertions.assertTrue(gate.started.await(5, TimeUnit.SECONDS),
                    "Both queries should start before either is released");
            gate.release.countDown();
            Order[] orders = result.get(5, TimeUnit.SECONDS);
            Assertions.assertEquals(1L, orders[0].getId());
            Assertions.assertEquals(2L, orders[1].getId());
        } finally {
            gate.release.countDown();
            gateMapper.setGate(null);
            caller.shutdownNow();
        }
    }

    @Test
    public void testMdcPropagationAndMutationSafety() {
        String testTraceId = "TRACE-MDC-" + System.currentTimeMillis();
        MDC.put("traceId", testTraceId);

        try {
            ContextCheckResult[] results = aggregationService.testMdcAndMutationSafety();
            ContextCheckResult asyncRead = results[0];
            ContextCheckResult syncWrite = results[1];

            // 1. Verify MDC propagation to a virtual thread.
            Assertions.assertEquals(testTraceId, asyncRead.getTraceId(), "MDC traceId should propagate to the async query thread");
            Assertions.assertTrue(asyncRead.isVirtualThread(), "Async queries should run on Java 21 virtual threads");

            // 2. Verify update methods stay on the caller, outside async dispatch.
            Assertions.assertFalse(syncWrite.isVirtualThread(), "Update methods must remain synchronous on the calling thread");
        } finally {
            MDC.clear();
        }
    }

    @Test
    public void testParallelTimeoutException() {
        // 1. With awaitAllOnExit=true, the scope times out on exit.
        ParallelTimeoutException scopeEx = Assertions.assertThrows(ParallelTimeoutException.class, () -> {
            aggregationService.testScopeTimeoutException();
        });
        Assertions.assertTrue(scopeEx.getMessage().contains("timed out"), "Scope timeout message should mention timed out");
        Assertions.assertEquals(150, scopeEx.getTimeoutMs(), "Scope timeout should expose its configured duration");

        // 2. With awaitAllOnExit=false, the timeout occurs on proxy access.
        Score score = aggregationService.testProxyDereferenceTimeout();
        ParallelTimeoutException proxyEx = Assertions.assertThrows(ParallelTimeoutException.class, () -> {
            score.getPoints();
        });
        Assertions.assertTrue(proxyEx.getMessage().contains("timed out"), "Proxy timeout message should mention timed out");
        Assertions.assertTrue(proxyEx.getTimeoutMs() > 0 && proxyEx.getTimeoutMs() <= 150, "Proxy timeout should expose the remaining duration");
    }

    @Test
    public void testBusinessExceptionPropagation() {
        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, () -> {
            aggregationService.testBusinessExceptionPropagation();
        });
        Assertions.assertEquals("Database connection pool exhausted", ex.getMessage(), "Business errors should propagate unchanged");
    }

    @Test
    public void testPrimaryBusinessExceptionNotMasked() {
        // Background waiting or timeouts must not hide the primary business error.
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            aggregationService.testPrimaryBusinessException();
        });
        Assertions.assertEquals("Explicit validation failed", ex.getMessage(), "Background timeouts must not hide business errors");
    }

    @Test
    public void testTransparentProxyObjectMethods() {
        OrderDetailVO parallelResult = aggregationService.getDetailParallel(12345L);
        Order order = parallelResult.getOrder();

        // Verify toString delegation.
        Assertions.assertNotNull(order.toString());
        // Verify hashCode delegation.
        Assertions.assertNotEquals(0, order.hashCode());
        // Verify unwrap returns the underlying entity.
        Object unwrapped = LazyProxyFactory.unwrap(order);
        Assertions.assertNotNull(unwrapped);
        Assertions.assertEquals(Order.class, unwrapped.getClass());
        Assertions.assertNotEquals(Order.class, order.getClass());
    }

    @Test
    public void testPrivateConstructorEntityProxy() {
        // ByteBuddy can proxy and unwrap a POJO with only a private constructor.
        PrivateConstructorEntity entity = aggregationService.testPrivateConstructor();
        Assertions.assertNotNull(entity);
        Assertions.assertEquals("PrivateConstructorSuccessful", entity.getTitle());
    }

    @Test
    public void testInterfaceNullPrimitiveSafety() {
        // Without a non-null contract, preserve the original null semantics.
        IStatsService stats = aggregationService.testNullInterface();
        Assertions.assertNull(stats);
        Assertions.assertNull(aggregationService.testAutoMapperNullable());
    }

    @Test
    public void testNonNullContractViolationIsReportedAtScopeExit() {
        ParallelQueryException error = Assertions.assertThrows(ParallelQueryException.class,
                () -> aggregationService.testInvalidNonNull());
        Assertions.assertTrue(error.getMessage().contains("returned null"));
    }

    @Test
    public void testConfiguredDefaultScopeTimeout() throws Throwable {
        ParallelProperties properties = new ParallelProperties();
        properties.setDefaultTimeoutMs(137);
        ParallelScopeAspect aspect = new ParallelScopeAspect(properties);
        ParallelScope annotation = AggregationService.class.getMethod("testAutoMapperNullable")
                .getAnnotation(ParallelScope.class);
        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        Mockito.when(joinPoint.proceed()).thenAnswer(invocation -> ParallelContext.currentScope().getTimeoutMs());
        Assertions.assertEquals(137L, aspect.aroundScope(joinPoint, annotation));
        Assertions.assertFalse(ParallelContext.isActive());
    }

    @Test
    public void testFinalGetterFallsBackToRealObject() {
        FinalGetterEntity value = LazyProxyFactory.createProxy(FinalGetterEntity.class,
                CompletableFuture.completedFuture(new FinalGetterEntity()), 1000);
        Assertions.assertEquals(FinalGetterEntity.class, value.getClass());
        Assertions.assertEquals("real", value.getValue());
    }

    @Test
    public void testPackagePrivateMethodFallsBackToRealObject() {
        Assertions.assertFalse(LazyProxyFactory.canProxy(PackagePrivateEntity.class));
        PackagePrivateEntity entity = aggregationService.testPackagePrivateEntity();
        Assertions.assertEquals(PackagePrivateEntity.class, entity.getClass());
        Assertions.assertEquals("real", entity.packageValue());
    }

    @Test
    public void testNonPublicAndSealedTypesFallBackToRealObject() {
        Assertions.assertFalse(LazyProxyFactory.canProxy(NonPublicEntity.class));
        Assertions.assertFalse(LazyProxyFactory.canProxy(SealedEntity.class));
        NonPublicEntity value = LazyProxyFactory.createProxy(NonPublicEntity.class,
                CompletableFuture.completedFuture(new NonPublicEntity()), 1000);
        Assertions.assertEquals(NonPublicEntity.class, value.getClass());
    }

    @Test
    public void testLargeTimeoutDoesNotOverflowProxyWait() {
        CompletableFuture<Score> future = new CompletableFuture<>();
        Score proxy = LazyProxyFactory.createProxy(Score.class, future, Long.MAX_VALUE);
        CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS)
                .execute(() -> future.complete(new Score(42)));
        Assertions.assertEquals(42, proxy.getPoints());
    }

    @Test
    public void testActiveTransactionRunsQueryOnCallingThread() {
        Assertions.assertFalse(aggregationService.testReadWithinTransaction().isVirtualThread());
    }

    @Test
    public void testClassLevelAnnotationOnInterface() {
        Assertions.assertTrue(aggregationService.testAnnotatedInterfaceClient().isVirtualThread());
    }

    @Test
    public void testUnannotatedMapperIsNotProxied() {
        Assertions.assertFalse(AopUtils.isAopProxy(plainMapper));
        Assertions.assertEquals("plain", plainMapper.selectValue());
    }

    @Test
    public void testCancellationInterruptsRunningTask() throws Exception {
        ParallelExecutor executor = new ParallelExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            CompletableFuture<String> future = executor.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
                return "late";
            });
            Assertions.assertTrue(started.await(2, TimeUnit.SECONDS));
            Assertions.assertTrue(future.cancel(true));
            Assertions.assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally {
            executor.destroy();
        }
    }

    @Test
    public void testParallelCapacityRejectsExcessAndReleasesAfterCompletion() throws Exception {
        ParallelProperties properties = new ParallelProperties();
        properties.setMaxConcurrentQueries(1);
        ParallelExecutor executor = new ParallelExecutor(properties, List.of());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> first = executor.submit(() -> {
                started.countDown();
                release.await();
                return "first";
            });
            Assertions.assertTrue(started.await(2, TimeUnit.SECONDS));
            Assertions.assertThrows(ParallelCapacityException.class, () -> executor.submit(() -> "excess"));
            release.countDown();
            Assertions.assertEquals("first", first.get(2, TimeUnit.SECONDS));
            Assertions.assertEquals("next", executor.submit(() -> "next").get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.destroy();
        }
    }

    @Test
    public void testCapacityFallbackAndExplicitRejection() throws Throwable {
        ParallelProperties properties = new ParallelProperties();
        properties.setMaxConcurrentQueries(1);
        ParallelExecutor executor = new ParallelExecutor(properties, List.of());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> blocker = executor.submit(() -> {
                started.countDown();
                release.await();
                return "done";
            });
            Assertions.assertTrue(started.await(2, TimeUnit.SECONDS));
            ParallelQueryAspect aspect = new ParallelQueryAspect(executor, properties);
            var method = MockContextAndMutationMapper.class.getMethod("selectContextInfo");
            AtomicInteger callerRuns = new AtomicInteger();
            ParallelContext.enter(5000, true);
            try {
                Object result = aspect.invokeQuery(method, MockContextAndMutationMapper.class, () -> {
                    callerRuns.incrementAndGet();
                    return new ContextCheckResult("caller", Thread.currentThread().isVirtual());
                });
                Assertions.assertEquals(ContextCheckResult.class, result.getClass());
                Assertions.assertFalse(((ContextCheckResult) result).isVirtualThread());
                Assertions.assertEquals(1, callerRuns.get());

                properties.setFallbackToCallerOnCapacity(false);
                Assertions.assertThrows(ParallelCapacityException.class,
                        () -> aspect.invokeQuery(method, MockContextAndMutationMapper.class, () -> {
                            callerRuns.incrementAndGet();
                            return new ContextCheckResult("unexpected", false);
                        }));
                Assertions.assertEquals(1, callerRuns.get());
            } finally {
                ParallelContext.exit();
            }
            release.countDown();
            Assertions.assertEquals("done", blocker.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.destroy();
        }
    }

    @Test
    public void testCanceledButStillRunningQueryKeepsCapacitySlot() throws Exception {
        ParallelProperties properties = new ParallelProperties();
        properties.setMaxConcurrentQueries(1);
        ParallelExecutor executor = new ParallelExecutor(properties, List.of());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> first = executor.submit(() -> {
                started.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // Simulate a downstream operation that does not stop on cancellation.
                    }
                }
                return "finished";
            });
            Assertions.assertTrue(started.await(2, TimeUnit.SECONDS));
            Assertions.assertTrue(first.cancel(true));
            Assertions.assertThrows(ParallelCapacityException.class, () -> executor.submit(() -> "excess"));
        } finally {
            release.countDown();
            executor.destroy();
        }
    }

    @Test
    public void testContextCaptureFailurePreventsQuery() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicInteger captures = new AtomicInteger();
        ContextPropagator broken = new ContextPropagator() {
            public Object capture() {
                if (captures.incrementAndGet() == 1) {
                    throw new IllegalStateException("capture failed");
                }
                return null;
            }
            public void restore(Object snapshot) {}
            public void clear() {}
        };
        ParallelProperties properties = new ParallelProperties();
        properties.setMaxConcurrentQueries(1);
        ParallelExecutor executor = new ParallelExecutor(properties, List.of(broken));
        try {
            ParallelQueryException error = Assertions.assertThrows(ParallelQueryException.class,
                    () -> executor.submit(() -> { executed.set(true); return "query"; }));
            Assertions.assertEquals("capture failed", error.getCause().getMessage());
            Assertions.assertFalse(executed.get());
            Assertions.assertEquals("next", executor.submit(() -> "next").join());
        } finally {
            executor.destroy();
        }
    }

    @Test
    public void testContextRestoreFailurePreventsQueryAndCleansUp() throws Exception {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicInteger clears = new AtomicInteger();
        ContextPropagator broken = new ContextPropagator() {
            public Object capture() { return "tenant"; }
            public void restore(Object snapshot) { throw new IllegalStateException("restore failed"); }
            public void clear() { clears.incrementAndGet(); }
        };
        ParallelExecutor executor = new ParallelExecutor(new ParallelProperties(), List.of(broken));
        try {
            CompletableFuture<String> future = executor.submit(() -> { executed.set(true); return "query"; });
            ExecutionException error = Assertions.assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            Assertions.assertEquals("restore failed", error.getCause().getMessage());
            Assertions.assertFalse(executed.get());
            Assertions.assertEquals(1, clears.get());
        } finally {
            executor.destroy();
        }
    }

    @Test
    public void testFirstFailureIsNotMaskedByAnotherQueryTimeout() {
        ParallelContext.enter(500, true);
        CompletableFuture<Object> failed = new CompletableFuture<>();
        CompletableFuture<Object> pending = new CompletableFuture<>();
        ParallelContext.currentScope().registerFuture(failed);
        ParallelContext.currentScope().registerFuture(pending);
        failed.completeExceptionally(new IllegalStateException("first failure"));
        IllegalStateException error = Assertions.assertThrows(IllegalStateException.class, ParallelContext::exit);
        Assertions.assertEquals("first failure", error.getMessage());
        Assertions.assertTrue(pending.isCancelled());
        Assertions.assertFalse(ParallelContext.isActive());
    }

    @Test
    public void testArrayDataHandling() {
        // Array return types fall back safely and remain usable.
        String[] data = aggregationService.testArrayData();
        Assertions.assertNotNull(data);
        Assertions.assertEquals(2, data.length);
        Assertions.assertEquals("elem1", data[0]);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
