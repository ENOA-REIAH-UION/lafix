package com.enoa.lafix;

import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 一次 onPackageLoaded 的安装上下文：持有模块实例与目标 ClassLoader，统一封装类 / 成员查找、
 * Hook 安装与日志。
 *
 * install() 会把单个 Hook 的安装异常收敛成一条日志后跳过，因此目标随版本变动时，其余修复仍能
 * 独立生效。
 */
final class HookScope {

    /** 单个 Hook 的安装动作；允许抛出反射相关异常。 */
    @FunctionalInterface
    interface Step {
        void install() throws Throwable;
    }

    private final XposedModule module;
    private final ClassLoader classLoader;

    private Class<?> observerClass;
    private Method otherDeskMethod;

    HookScope(XposedModule module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    /** 按名称载入目标类（不触发静态初始化）。 */
    Class<?> type(String className) throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    /** 查找方法：先 getMethod，失败再退回 getDeclaredMethod 并开放访问。 */
    Method method(Class<?> owner, String name, Class<?>... parameterTypes)
            throws ReflectiveOperationException {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        }
    }

    /** 查找字段：先 getField，失败再退回 getDeclaredField 并开放访问。 */
    Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        try {
            return owner.getField(name);
        } catch (NoSuchFieldException e) {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }

    /**
     * OverviewComponentObserver.isOtherDesk()：本桌面是否为「非默认 Home」。系统桌面内一批私有
     * 实现都以上游判定为准，本模块用它决定各修复是否应当生效。
     */
    Method otherDesk() throws ReflectiveOperationException {
        if (otherDeskMethod == null) {
            if (observerClass == null) observerClass = type(Targets.OVERVIEW_OBSERVER);
            otherDeskMethod = method(observerClass, "isOtherDesk");
        }
        return otherDeskMethod;
    }

    /** 调用 otherDesk()；失败时按「是默认 Home」处理，即保持原行为。 */
    boolean isThirdPartyHome(Method otherDesk) {
        try {
            return Boolean.TRUE.equals(otherDesk.invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 让某个无参布尔方法在第三方桌面下固定返回 value，其余情况放行原实现。用于把 ColorOS 挂在
     * 「是否默认 Home」上的开关统一到正常分支。
     */
    void forceBooleanWhenThirdPartyHome(String ownerClassName, String methodName,
                                        boolean value, String label) {
        install(label, () -> {
            Method otherDesk = otherDesk();
            Method target = method(type(ownerClassName), methodName);
            hook(target).intercept(chain ->
                    isThirdPartyHome(otherDesk) ? Boolean.valueOf(value) : chain.proceed());
        });
    }

    XposedInterface.HookBuilder hook(Executable target) {
        return module.hook(target);
    }

    /** 安装单个 Hook；失败只记录日志，不向外抛出。 */
    void install(String label, Step step) {
        try {
            step.install();
            Log.i(MainHook.TAG, "hooked: " + label);
        } catch (Throwable t) {
            Log.w(MainHook.TAG, label + " unsupported, skipped: " + t);
            try {
                module.log(Log.WARN, MainHook.TAG, label + " unsupported: " + t);
            } catch (Throwable ignored) {
                // 框架日志不可用时忽略。
            }
        }
    }
}
