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
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Finds query annotations declared on interfaces. AspectJ execution pointcuts
 * inspect the implementation method and can miss these declarations.
 */
public class InterfaceParallelQueryAdvisor extends StaticMethodMatcherPointcutAdvisor {

    public InterfaceParallelQueryAdvisor(ParallelQueryAspect aspect) {
        super((MethodInterceptor) invocation -> aspect.invokeQuery(
                invocation.getMethod(),
                AopUtils.getTargetClass(invocation.getThis()),
                invocation::proceed));
        Objects.requireNonNull(aspect, "ParallelQueryAspect must not be null");
        setOrder(200);
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        if (AnnotatedElementUtils.getMergedAnnotation(specificMethod, ParallelQuery.class) != null
                || AnnotatedElementUtils.getMergedAnnotation(targetClass, ParallelQuery.class) != null) {
            return false;
        }
        for (Class<?> ifc : ClassUtils.getAllInterfacesForClassAsSet(targetClass)) {
            if (AnnotatedElementUtils.findMergedAnnotation(ifc, ParallelQuery.class) != null) {
                return true;
            }
            Method interfaceMethod = ReflectionUtils.findMethod(ifc, method.getName(), method.getParameterTypes());
            if (interfaceMethod != null
                    && AnnotatedElementUtils.findMergedAnnotation(interfaceMethod, ParallelQuery.class) != null) {
                return true;
            }
        }
        return false;
    }
}
