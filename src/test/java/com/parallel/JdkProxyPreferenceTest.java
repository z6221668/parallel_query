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

import com.parallel.annotation.ParallelQuery;
import com.parallel.annotation.ParallelScope;
import com.parallel.config.ParallelQueryAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicInteger;

class JdkProxyPreferenceTest {

    private static final AtomicInteger QUERY_CALLS = new AtomicInteger();

    interface Client {
        @ParallelQuery(nonNullResult = true)
        Result query();
    }

    static class ClientImpl implements Client {
        @Override
        public Result query() {
            QUERY_CALLS.incrementAndGet();
            return new Result(Thread.currentThread().isVirtual());
        }
    }

    interface Facade {
        Result load();
    }

    static class FacadeImpl implements Facade {
        private final Client client;

        FacadeImpl(Client client) {
            this.client = client;
        }

        @Override
        @ParallelScope
        public Result load() {
            return client.query();
        }
    }

    public static class Result {
        private final boolean virtual;

        Result(boolean virtual) {
            this.virtual = virtual;
        }

        public boolean isVirtual() {
            return virtual;
        }
    }

    @Test
    void respectsHostJdkProxyPreference() {
        QUERY_CALLS.set(0);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                        ParallelQueryAutoConfiguration.class))
                .withPropertyValues("spring.aop.proxy-target-class=false")
                .withBean(Client.class, ClientImpl::new)
                .withBean(FacadeImpl.class)
                .run(context -> {
                    Assertions.assertNull(context.getStartupFailure());
                    Assertions.assertTrue(AopUtils.isJdkDynamicProxy(context.getBean(Client.class)));
                    Assertions.assertTrue(AopUtils.isJdkDynamicProxy(context.getBean(Facade.class)));
                    Assertions.assertTrue(context.getBean(Facade.class).load().isVirtual());
                    Assertions.assertEquals(1, QUERY_CALLS.get());
                });
    }
}
