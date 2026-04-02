package com.github.intfoo.agent;

import com.intellij.openapi.application.ApplicationManager;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

public class StaticHelper {

    public static Object runInReadAction(
            Object target,
            String methodName,
            Object[] params,
            Class[] paramTypes) throws Exception {

        if (ApplicationManager.getApplication().isReadAccessAllowed()) {
            // 已在 ReadAction 中，直接调用
            Method m = target.getClass().getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m.invoke(target, params);
        }

        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        ApplicationManager.getApplication().runReadAction(() -> {
            try {
                Method m = target.getClass().getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                result.set(m.invoke(target, params));
            } catch (Throwable e) {
                error.set(e);
            }
        });

        if (error.get() != null) throw new RuntimeException(error.get());
        return result.get();
    }
}