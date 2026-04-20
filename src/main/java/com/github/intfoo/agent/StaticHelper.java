package com.github.intfoo.agent;

import com.intellij.openapi.application.ApplicationManager;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

public class StaticHelper {

        public static Object runInReadAction(
                Object target,
                String className,
                ClassLoader classLoader,   // ← 新增
                String methodName,
                Object[] params,
                Class[] paramTypes) throws Exception {

            if (ApplicationManager.getApplication().isReadAccessAllowed()) {
                return invoke(target, className, classLoader, methodName, params, paramTypes);
            }

            AtomicReference<Object> result = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();

            ApplicationManager.getApplication().runReadAction(() -> {
                try {
                    result.set(invoke(target, className, classLoader, methodName, params, paramTypes));
                } catch (Throwable e) {
                    error.set(e);
                }
            });

            if (error.get() != null) throw new RuntimeException(error.get());
            return result.get();
        }

        private static Object invoke(Object target, String className,
                                     ClassLoader classLoader,
                                     String methodName, Object[] params,
                                     Class[] paramTypes) throws Exception {
            if (target != null) {
                // 实例方法
                Method m = target.getClass().getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m.invoke(target, params);
            } else {
                // 静态方法：用传入的 ClassLoader 加载，而不是 bootstrap
                Class<?> clazz = Class.forName(className, true, classLoader);
                Method m = clazz.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m.invoke(null, params);
            }
        }
    }