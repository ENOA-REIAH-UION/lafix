package com.enoa.lafix;

import android.app.Activity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * App → Overview 转场超时兜底（AppToOverviewAnimationProvider 的 1000ms 超时，作用范围：系统桌面）。
 *
 * 原兜底只对真 Launcher 生效：超时后取 Launcher.ACTIVITY_TRACKER 判空并 reapplyState()。第三方桌面
 * 场景没有 Launcher，兜底退化为空操作，转场一旦卡住便无法自愈。
 *
 * 处置：在原逻辑之后补一次等价恢复——改取 RecentsActivity.ACTIVITY_TRACKER 当前承载概览的 Activity，
 * 重新应用其状态。仅在第三方桌面下追加，恢复过程失败不影响原行为。
 */
final class TransitionFixes {

    private TransitionFixes() {
    }

    static void install(HookScope scope) {
        scope.install("transition-timeout-recovery", () -> {
            Class<?> provider = scope.type(Targets.APP_TO_OVERVIEW_PROVIDER);
            Class<?> observer = scope.type(Targets.OVERVIEW_OBSERVER);
            Class<?> recentsActivity = scope.type(Targets.RECENTS_ACTIVITY);
            Class<?> tracker = scope.type(Targets.ACTIVITY_TRACKER);
            Class<?> statefulActivity = scope.type(Targets.STATE_ACTIVITY);
            Class<?> stateManager = scope.type(Targets.STATE_MANAGER);

            Method otherDesk = scope.otherDesk();
            Method run = scope.method(timeoutRunnableClass(scope, provider), "run");
            Method getCreatedActivity = scope.method(tracker, "getCreatedActivity");
            Method getStateManager = scope.method(statefulActivity, "getStateManager");
            Method reapplyState = scope.method(stateManager, "reapplyState");
            Field trackerField = scope.field(recentsActivity, "ACTIVITY_TRACKER");

            scope.hook(run).intercept(chain -> {
                Object result = chain.proceed(); // 保留原逻辑（含解除 interrupt 标志）
                if (!scope.isThirdPartyHome(otherDesk)) return result;
                try {
                    Object activity = getCreatedActivity.invoke(trackerField.get(null));
                    if (activity instanceof Activity && !((Activity) activity).isDestroyed()
                            && statefulActivity.isInstance(activity)) {
                        Object manager = getStateManager.invoke(activity);
                        if (manager != null) reapplyState.invoke(manager);
                    }
                } catch (Throwable ignored) {
                    // 恢复失败时保持原行为。
                }
                return result;
            });
        });
    }

    /**
     * 取 1000ms 超时 Runnable 的运行时类。优先通过持有它的静态字段拿到实例（匿名类名 $1 的序号会随
     * 版本漂移，不宜写死）；字段不可用时退回类名字面量。
     */
    private static Class<?> timeoutRunnableClass(HookScope scope, Class<?> provider)
            throws ReflectiveOperationException {
        try {
            Field field = scope.field(provider, Targets.APP_TO_OVERVIEW_TIMEOUT_FIELD);
            Object instance = field.get(null);
            if (instance != null) return instance.getClass();
        } catch (Throwable ignored) {
            // 字段缺失或不可读时退回字面量。
        }
        return scope.type(Targets.APP_TO_OVERVIEW_TIMEOUT);
    }
}
