package com.enoa.lafix;

/**
 * 反射目标的名称清单，按作用域分组。
 *
 * 系统桌面（16.4.5，versionCode 160040005）的类名是真实名，取自反编译工程
 * /storage/emulated/0/MT2/apks/系统桌面_16.4.5_src/；智能侧边栏（16.16.2）同理，取自
 * /storage/emulated/0/MT2/apks/智能侧边栏_16.16.2_src/。
 *
 * 目标缺失时安装日志会记 unsupported，对应修复自动跳过，其余修复不受影响。
 */
final class Targets {

    private Targets() {
    }

    // ---- 系统桌面 ----

    /** 默认 Home 判定的中心，最近任务与转场的私有实现都挂在它上面。 */
    static final String OVERVIEW_OBSERVER = "com.android.quickstep.OverviewComponentObserver";

    /** 手势 / 按键服务主体，任务键派发与 Home 键模拟动画的入口。 */
    static final String TOUCH_SERVICE = "com.android.quickstep.OplusBaseTouchInteractionService";

    /**
     * 任务键派发所在的合成 lambda。决策只存在于这个合成方法里，名字由构建工具生成，跨版本可能变号；
     * 解析时会用下面的前缀做结构回退（见 RecentsKeyFixes.resolveTaskDispatch）。
     */
    static final String TASK_DISPATCH_METHOD = "onOverviewToggle$lambda$12$lambda$8";

    /** 任务键派发 lambda 的名字前缀，源自真实方法名 onOverviewToggle。 */
    static final String TASK_DISPATCH_PREFIX = "onOverviewToggle$lambda$";

    static final String FALLBACK_SWIPE_HANDLER = "com.android.quickstep.FallbackSwipeHandler";
    static final String RECT_TRANSFORM_HELPER =
            "com.android.quickstep.util.animation.RectTransformHelper";
    static final String SURFACE_PROPERTIES =
            "com.android.quickstep.util.SurfaceTransaction$SurfaceProperties";
    static final String REMOTE_ANIMATION_TARGET =
            "com.android.systemui.shared.system.RemoteAnimationTargetCompat";

    static final String DEVICE_STATE = "com.android.quickstep.RecentsAnimationDeviceState";
    static final String TASK_ANIMATION_MANAGER = "com.android.quickstep.TaskAnimationManager";

    static final String RECENTS_ACTIVITY = "com.android.quickstep.RecentsActivity";
    static final String RECENTS_VIEW = "com.android.quickstep.views.RecentsView";

    /** fallback 概览的承载视图；getHomeTaskView() 可识别「桌面自身那张卡」。 */
    static final String FALLBACK_RECENTS_VIEW =
            "com.android.quickstep.fallback.FallbackRecentsView";
    static final String OP_RECENTS_VIEW = "com.android.quickstep.views.OplusRecentsViewImpl";
    static final String TASK_VIEW = "com.android.quickstep.views.TaskView";
    static final String OP_TASK_VIEW = "com.android.quickstep.views.OplusTaskViewImpl";

    static final String ACTIVITY_INTERFACE = "com.android.quickstep.BaseActivityInterface";
    static final String BASE_ACTIVITY = "com.android.launcher3.BaseActivity";
    static final String BASE_DRAGGING_ACTIVITY = "com.android.launcher3.BaseDraggingActivity";
    static final String STATE_ACTIVITY = "com.android.launcher3.statemanager.StatefulActivity";
    static final String STATE_MANAGER = "com.android.launcher3.statemanager.StateManager";
    static final String ACTIVITY_TRACKER = "com.android.launcher3.util.ActivityTracker";
    static final String DEVICE_PROFILE = "com.android.launcher3.DeviceProfile";

    /** other-desk 判定的转发层：只改它，不动 OverviewComponentObserver 的真定义。 */
    static final String STACK_RECENTS_CONFIG =
            "com.oplus.quickstep.layout.stack.OplusStackRecentsConfig";
    static final String RECENTS_VIEW_ANIM_UTIL = "com.oplus.quickstep.utils.RecentsViewAnimUtil";
    static final String SWIPE_UP_HANDLER = "com.oplus.quickstep.gesture.OplusBaseSwipeUpHandler";
    static final String STACK_PAGED_VIEW = "com.oplus.quickstep.views.StackPagedViewEx";

    static final String PAGED_ORIENTATION_HANDLER =
            "com.android.quickstep.touch.OplusBasePagedOrientationHandler";
    static final String SCROLL_STATE =
            "com.android.quickstep.touch.OplusBasePagedOrientationHandler$ScrollState";

    static final String APP_TO_OVERVIEW_PROVIDER =
            "com.android.quickstep.AppToOverviewAnimationProvider";

    /** 持有 1000ms 转场超时 Runnable 的静态字段（字段名来自原厂源码，含原厂拼写）。 */
    static final String APP_TO_OVERVIEW_TIMEOUT_FIELD =
            "APP_TO_OVERVIE_WWINDOW_ANIM_TIMEOUT_RUNNABLE";

    /** 上述超时 Runnable 的类名字面量；仅在字段不可用时作为回退。 */
    static final String APP_TO_OVERVIEW_TIMEOUT =
            "com.android.quickstep.AppToOverviewAnimationProvider$1";

    // ---- 智能侧边栏 ----

    /** 最近应用数据源：从 UsageStatsManager 聚合出最近使用列表。 */
    static final String SIDEBAR_RECENT_SOURCE =
            "com.oplus.smartsidebar.panelview.edgepanel.recentapp.RecentAppSource$Companion";

    /** 最近应用列表元素；getPkg() 给出包名。 */
    static final String SIDEBAR_ENTRY_BEAN =
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.beans.EntryBean";

    /** 侧边栏的 Application 持有者，静态字段 sContext 即当前进程的 Context。 */
    static final String SIDEBAR_CONTEXT_HOLDER = "com.coloros.common.App";
}
