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

package com.parallel.integration;

import com.parallel.annotation.ParallelQuery;
import com.parallel.annotation.ParallelScope;
import com.parallel.executor.ParallelExecutor;
import com.parallel.proxy.LazyProxyFactory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SpringBootTest(classes = MyBatisIntegrationTest.App.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:parallel_my_batis;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "parallel.max-concurrent-queries=1"
})
class MyBatisIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(OrderService.class)
    static class App {
        @Bean
        MapperFactoryBean<OrderMapper> orderMapper(SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<OrderMapper> factory = new MapperFactoryBean<>(OrderMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }
    }

    public interface OrderMapper {
        @Select("select id, name from orders where id = #{id}")
        @ParallelQuery(nonNullResult = true)
        OrderRow selectRequired(@Param("id") long id);
    }

    public static class OrderRow {
        private Long id;
        private String name;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class OrderService {
        private final OrderMapper mapper;

        OrderService(OrderMapper mapper) {
            this.mapper = mapper;
        }

        @ParallelScope
        public OrderRow load(long id) {
            return mapper.selectRequired(id);
        }

        @Transactional
        @ParallelScope
        public OrderRow loadWithinTransaction(long id) {
            return mapper.selectRequired(id);
        }
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    OrderService service;

    @Autowired
    ParallelExecutor parallelExecutor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("create table if not exists orders (id bigint primary key, name varchar(50))");
        jdbcTemplate.update("merge into orders (id, name) key(id) values (?, ?)", 1L, "real-db");
    }

    @Test
    void mapperInterfaceAnnotationRunsThroughRealDatabase() {
        OrderRow result = service.load(1L);
        Assertions.assertInstanceOf(LazyProxyFactory.LazyWrapper.class, result);
        Assertions.assertEquals(1L, result.getId());
        Assertions.assertEquals("real-db", result.getName());
    }

    @Test
    void callerTransactionKeepsQuerySynchronous() {
        OrderRow result = service.loadWithinTransaction(1L);
        Assertions.assertEquals(OrderRow.class, result.getClass());
        Assertions.assertEquals("real-db", result.getName());
    }

    @Test
    void saturatedMapperQueryFallsBackToCallerThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> blocker = parallelExecutor.submit(() -> {
                started.countDown();
                release.await();
                return "done";
            });
            Assertions.assertTrue(started.await(2, TimeUnit.SECONDS));
            OrderRow result = service.load(1L);
            Assertions.assertEquals(OrderRow.class, result.getClass());
            Assertions.assertEquals("real-db", result.getName());
            release.countDown();
            Assertions.assertEquals("done", blocker.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}
