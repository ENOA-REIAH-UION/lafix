package com.enoa.lafix;

import android.app.Activity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * fallback 概览的共享判定。
 *
 * 第三方桌面为默认 Home 时，概览由独立的 RecentsActivity 承载。任务键派发、Home 键处置与返回键
 * 处置都要判断「概览是否在前台、承载视图是谁、运行任务卡是哪张」，故集中在这里；反射句柄在
 * resolve() 时一次性取出。
 *
 * 所有取值失败都返回 null，由调用方决定是否回退到原厂行为。
 */
final class RecentsOverview {

    private final Field trackerField;
    private final Method getCreatedActivity;
    private final Method isStarted;
    private final Method isPaused;
    private final Method getOverviewPanel;
    private final Method getRunningTaskView;
    /** 可选判据：识别「桌面自身那张卡」；目标缺失时为 null，该判据自动失效。 */
    private final Method getHomeTaskView;

    private RecentsOverview(Field trackerField, Method getCreatedActivity, Method isStarted,
                            Method isPaused, Method getOverviewPanel, Method getRunningTaskView,
                            Method getHomeTaskView) {
        this.trackerField = trackerField;
        this.getCreatedActivity = getCreatedActivity;
        this.isStarted = isStarted;
        this.isPaused = isPaused;
        this.getOverviewPanel = getOverviewPanel;
        this.getRunningTaskView = getRunningTaskView;
        this.getHomeTaskView = getHomeTaskView;
    }

    static RecentsOverview resolve(HookScope scope) throws ReflectiveOperationException {
        Class<?> recentsActivity = scope.type(Targets.RECENTS_ACTIVITY);
        Class<?> tracker = scope.type(Targets.ACTIVITY_TRACKER);
        Class<?> baseActivity = scope.type(Targets.BASE_ACTIVITY);
        Class<?> baseDraggingActivity = scope.type(Targets.BASE_DRAGGING_ACTIVITY);
        Class<?> recentsView = scope.type(Targets.RECENTS_VIEW);

        Method getHomeTaskView = null;
        try {
            getHomeTaskView = scope.method(
                    scope.type(Targets.FALLBACK_RECENTS_VIEW), "getHomeTaskView");
        } catch (Throwable ignored) {
            // 可选判据，缺失时退化为「不排除桌面卡」。
        }

        return new RecentsOverview(
                scope.field(recentsActivity, "ACTIVITY_TRACKER"),
                scope.method(tracker, "getCreatedActivity"),
                scope.method(baseActivity, "isStarted"),
                scope.method(baseActivity, "isPaused"),
                scope.method(baseDraggingActivity, "getOverviewPanel"),
                scope.method(recentsView, "getRunningTaskView"),
                getHomeTaskView);
    }

    /** 概览正展示在前台（含正在进入的动画）时返回承载它的 RecentsActivity，否则返回 null。 */
    Activity showing() {
        try {
            Object tracker = trackerField.get(null);
            if (tracker == null) return null;
            Object activity = getCreatedActivity.invoke(tracker);
            if (!(activity instanceof Activity)) return null;
            Activity recents = (Activity) activity;
            if (recents.isDestroyed() || recents.isFinishing()) return null;
            if (!Boolean.TRUE.equals(isStarted.invoke(recents))) return null;
            return Boolean.TRUE.equals(isPaused.invoke(recents)) ? null : recents;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 取活动的概览承载视图（fallback 下即 FallbackRecentsView）；取不到返回 null。 */
    Object panel(Activity activity) {
        try {
            return activity == null ? null : getOverviewPanel.invoke(activity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 取「进入概览前的前台任务」对应的卡片视图；取不到返回 null。 */
    Object runningTaskView(Object recentsView) {
        try {
            return recentsView == null ? null : getRunningTaskView.invoke(recentsView);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 取 fallback 概览里代表「桌面自身」的那张卡（从主屏打开概览时，运行任务就是它）。判定缺失或
     * 取不到时返回 null，调用方按「不是桌面卡」处理。
     */
    Object homeTaskView(Object panel) {
        if (getHomeTaskView == null) return null;
        try {
            return panel == null ? null : getHomeTaskView.invoke(panel);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
