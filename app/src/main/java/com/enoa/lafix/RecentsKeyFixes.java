package com.enoa.lafix;

import android.app.Activity;
import android.content.ComponentName;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 「任务键 / 最近任务列表」相关修复（作用范围：系统桌面）。
 *
 * 主线是把 other-desk 判定统一到 false。ColorOS 把最近任务的整套私有实现（命令队列、动画完成
 * 回调、堆叠样式、Dock、清除面板等）挂在 OplusStackRecentsConfig.isOtherDesk() 上；第三方桌面下
 * 这些能力被整体判为不支持，于是出现路径不一致、守卫失效、回调缺失。只改这一处，下游便一致走
 * 「默认 Home」分支。
 *
 * 例外是任务键派发：第三方桌面下概览由独立 RecentsActivity 承载，而命令队列派发以「概览由
 * Launcher 自身承载」为前提，两者混用会造成手势式输入接管与动画重放，故单独对齐
 * （见 alignTaskKeyDispatch）。
 */
final class RecentsKeyFixes {

    private RecentsKeyFixes() {
    }

    static void install(HookScope scope) {
        // 治本点：只 hook OplusStackRecentsConfig 这个转发层，不动 OverviewComponentObserver.isOtherDesk()
        // 的真定义——那会把「本桌面是否为默认 Home」全线翻转，连 Home 键的 intent 选取都会出错。
        scope.forceBooleanWhenThirdPartyHome(
                Targets.STACK_RECENTS_CONFIG, "isOtherDesk", false,
                "OplusStackRecentsConfig#isOtherDesk");

        alignTaskKeyDispatch(scope);
        guardRecentsViewCast(scope);
        fixCardInsertionInterpolation(scope);
        alignOverviewFocusTarget(scope);
        fixBackKeyFromOverview(scope);

        // 放宽「同一任务、距上次启动不足 1s 就拒绝」的守卫，避免连点被吞。
        scope.forceBooleanWhenThirdPartyHome(
                Targets.RECENTS_VIEW_ANIM_UTIL, "checkLaunchTaskLongestTimeGap", true,
                "RecentsViewAnimUtil#checkLaunchTaskLongestTimeGap");
    }

    /**
     * 症状：任务键偶发无响应，且系统桌面的手势服务进程被杀。
     *
     * 根因：OplusBaseTouchInteractionService.getRecentsView() 把当前 Activity 无条件强转为 Launcher；
     * 第三方桌面下承载概览的独立 RecentsActivity 与 Launcher 是兄弟类，强转抛 ClassCastException，
     * 沿派发链一路上抛、无人捕获。
     *
     * 处置：第三方桌面下改走安全的等价取值——getActivityInterface().getCreatedActivity()
     * .getOverviewPanel()；取不到则返回 null，使派发点安全回退到冷启动路径。
     */
    private static void guardRecentsViewCast(HookScope scope) {
        scope.install("recents-view-cast-guard", () -> {
            Class<?> service = scope.type(Targets.TOUCH_SERVICE);
            Class<?> observer = scope.type(Targets.OVERVIEW_OBSERVER);
            Class<?> activityInterface = scope.type(Targets.ACTIVITY_INTERFACE);
            Class<?> baseActivity = scope.type(Targets.BASE_DRAGGING_ACTIVITY);
            Class<?> recentsView = scope.type(Targets.OP_RECENTS_VIEW);

            Method otherDesk = scope.otherDesk();
            Method getActivityInterface = scope.method(observer, "getActivityInterface");
            Method getCreatedActivity = scope.method(activityInterface, "getCreatedActivity");
            Method getOverviewPanel = scope.method(baseActivity, "getOverviewPanel");
            Method getRecentsView = scope.method(service, "getRecentsView");
            Field observerField = scope.field(service, "mOverviewComponentObserver");

            scope.hook(getRecentsView).intercept(chain -> {
                if (!scope.isThirdPartyHome(otherDesk)) return chain.proceed();
                try {
                    Object observerInstance = observerField.get(chain.getThisObject());
                    if (observerInstance == null) return null;
                    Object interfaceInstance = getActivityInterface.invoke(observerInstance);
                    if (interfaceInstance == null) return null;
                    Object activity = getCreatedActivity.invoke(interfaceInstance);
                    if (activity == null || ((Activity) activity).isDestroyed()) return null;
                    Object overviewPanel = getOverviewPanel.invoke(activity);
                    return recentsView.isInstance(overviewPanel) ? overviewPanel : null;
                } catch (Throwable ignored) {
                    return null; // 取值失败 → 交回冷启动路径
                }
            });
        });
    }

    /**
     * 任务键派发对齐：概览未展开时冷启动打开，已在前台时保留原厂命令队列。
     *
     * 派发点的分支条件为：
     *   isSupportMultiTapMenu() &amp;&amp; getRecentsView() != null &amp;&amp; !isRecentTasksEmpty()
     *       &amp;&amp; !fromThirdPartyHome  → 命令队列 addCommand(4)
     *   否则 → RecentsActivityCommand.run()（冷启动）
     *
     * 不能简单放开命令队列：统一开关让 isSupportMultiTapMenu() 恒为 true，而 getRecentsView() 只要
     * RecentsActivity 实例还在就非空——包括概览并未显示的时候。此时命令队列会按「Launcher 内概览」
     * 的方式打开概览，即手势式输入接管 / 动画重放。
     *
     * 也不能一律冷启动：冷启动路径的判断要求概览已 resumed，进入动画尚未结束时会被判为不可见，
     * 快速连按会落进双按守卫被静默吞掉。
     *
     * 因此仅第三方桌面下分流：概览已在前台（isStarted() 且未 paused，含正在进入的动画）→ 交回原有
     * 派发，走命令队列 addCommand(4)，由它自带的 500ms 合并、延迟执行与动画互斥守卫处理连按；概览
     * 未展开 → 把派发布尔强制为 true，与「在 Home 上」一致地冷启动打开。
     */
    private static void alignTaskKeyDispatch(HookScope scope) {
        scope.install("task-key-dispatch", () -> {
            Class<?> service = scope.type(Targets.TOUCH_SERVICE);
            RecentsOverview overview = RecentsOverview.resolve(scope);

            Method otherDesk = scope.otherDesk();
            Method taskDispatch = resolveTaskDispatch(scope, service);

            scope.hook(taskDispatch).intercept(chain -> {
                if (!scope.isThirdPartyHome(otherDesk)) return chain.proceed();

                // 概览已在前台 → 保留原厂命令队列（多击遍历任务）。
                if (overview.showing() != null) return chain.proceed();

                // 概览未展开 → 视作顶层是第三方 Home，冷启动打开。
                Object[] args = chain.getArgs().toArray();
                if (args.length < 2) return chain.proceed();
                args[1] = Boolean.TRUE;
                return chain.proceed(args);
            });
        });
    }

    /**
     * 定位任务键派发方法。派发决策只存在于 R8 生成的合成 lambda 里，没有真实名可用，因此先用登记的
     * 名字，缺失时再按结构查找：名字以 onOverviewToggle 的 lambda 前缀开头、签名精确匹配
     * (本服务, boolean)，且自身是内层 lambda（名字里出现两次 $lambda$；外层那次只出现一次）。
     * 命中多个说明结构已变，此时放弃而不是赌一个。
     */
    private static Method resolveTaskDispatch(HookScope scope, Class<?> service)
            throws ReflectiveOperationException {
        try {
            return scope.method(service, Targets.TASK_DISPATCH_METHOD, service, boolean.class);
        } catch (ReflectiveOperationException exactMissing) {
            Method found = null;
            for (Method candidate : service.getDeclaredMethods()) {
                String name = candidate.getName();
                if (!name.startsWith(Targets.TASK_DISPATCH_PREFIX)
                        || name.indexOf("$lambda$") == name.lastIndexOf("$lambda$")
                        || candidate.getParameterCount() != 2
                        || candidate.getParameterTypes()[0] != service
                        || candidate.getParameterTypes()[1] != boolean.class) {
                    continue;
                }
                if (found != null) throw exactMissing; // 有歧义就不猜
                found = candidate;
            }
            if (found == null) throw exactMissing;
            found.setAccessible(true);
            Log.i(MainHook.TAG, "task-key-dispatch resolved as " + found.getName());
            return found;
        }
    }

    /**
     * 返回键：概览里按返回应回到「打开概览前的前台应用」，而不是桌面。
     *
     * 根因：原厂 OplusBaseRecentsActivity.onBackPressed() 已实现该行为——有运行任务就
     * launchTaskAnimated()，否则退回 Activity.onBackPressed()；但 RecentsActivity 把它覆写成了
     * startHome()，于是返回一律回桌面。stock 场景下概览由 Launcher 自身承载，返回取
     * getRunningTaskView() 并启动它，因此只有 fallback 有这个差异。
     *
     * 处置：第三方桌面下改回父类语义——取概览承载视图的运行任务卡并启动；取不到则放行原实现
     * （回桌面）。运行任务指「打开概览前的前台任务」，与当前滚动到第几页无关。
     *
     * 排除「桌面自身那张卡」：fallback 会把桌面也作为一张卡放进列表，从主屏打开概览时运行任务就是
     * 它；此时保持原实现（回桌面），而不是从卡片启动桌面任务。
     */
    private static void fixBackKeyFromOverview(HookScope scope) {
        scope.install("overview-back-key", () -> {
            RecentsOverview overview = RecentsOverview.resolve(scope);
            Class<?> recentsActivity = scope.type(Targets.RECENTS_ACTIVITY);

            Method otherDesk = scope.otherDesk();
            Method onBackPressed = scope.method(recentsActivity, "onBackPressed");
            Method launchTaskAnimated =
                    scope.method(scope.type(Targets.TASK_VIEW), "launchTaskAnimated");

            scope.hook(onBackPressed).intercept(chain -> {
                if (!scope.isThirdPartyHome(otherDesk)) return chain.proceed();

                Object self = chain.getThisObject();
                Activity recents = self instanceof Activity ? (Activity) self : null;
                Object panel = overview.panel(recents);
                Object runningTask = overview.runningTaskView(panel);
                if (runningTask == null || runningTask == overview.homeTaskView(panel)) {
                    return chain.proceed(); // 无前台应用 / 运行任务就是桌面自身
                }

                try {
                    launchTaskAnimated.invoke(runningTask);
                    return null; // 消费这次返回，不再走 startHome()
                } catch (Throwable ignored) {
                    return chain.proceed(); // 启动失败时交还原实现
                }
            });
        });
    }

    /**
     * 症状：进入概览时「居中聚焦」的卡与实际切换到的任务不是同一张（跳转对、聚焦错）。
     *
     * 根因：跳转目标由 getNextTask() 给出，无运行任务时取第 1 张；而「自动聚焦到下一页」的目标由
     * getFocusToNextPageTarget() 计算，取 currentPage + 1。从主屏进入时 currentPage = 0，实际跳转
     * 目标页也是 0，两套基准不一致，焦点与整页滚动被推到第 2 张卡。
     *
     * 处置：仅第三方桌面，且仅当原实现确实给出了要聚焦的页（返回值 ≥ 0，-1 表示它判定不聚焦）时，
     * 把结果改为运行任务所在页 0，使聚焦与跳转同源。
     */
    private static void alignOverviewFocusTarget(HookScope scope) {
        scope.install("overview-focus-target", () -> {
            Class<?> provider = scope.type(Targets.APP_TO_OVERVIEW_PROVIDER);
            Class<?> recentsView = scope.type(Targets.RECENTS_VIEW);
            Class<?> swipeUpHandler = scope.type(Targets.SWIPE_UP_HANDLER);
            Class<?> observer = scope.type(Targets.OVERVIEW_OBSERVER);
            Class<?> animationUtil = scope.type(Targets.RECENTS_VIEW_ANIM_UTIL);
            RecentsOverview overview = RecentsOverview.resolve(scope);

            Method otherDesk = scope.otherDesk();
            // getPkgName() 是 private，可能被内联；改用公开的 getDefaultHome()（语义同为默认 Home）。
            Method getDefaultHome = scope.method(observer, "getDefaultHome");
            Method getWindowAnimationPackage =
                    scope.method(animationUtil, "getMWindowAnimationPkg");
            Method getFocusTarget = scope.method(provider, "getFocusToNextPageTarget",
                    boolean.class, recentsView, swipeUpHandler);
            Field animationUtilInstance = scope.field(animationUtil, "INSTANCE");

            scope.hook(getFocusTarget).intercept(chain -> {
                Object result = chain.proceed();
                // 原实现返回 -1 表示它判定不聚焦（未进入概览、仅一页、已翻过页等），此时不覆盖。
                if (!(result instanceof Integer) || (Integer) result < 0) return result;
                if (!scope.isThirdPartyHome(otherDesk)) return result;
                try {
                    Object recents = chain.getArg(1);
                    if (recents == null) return result;

                    // 「是否从主屏进入」用两道判据确认：没有运行任务视图，或本次窗口动画的来源包名
                    // 为空 / 等于默认 Home 包名（进入首帧的运行任务视图可能是残留状态）。
                    if (overview.runningTaskView(recents) == null) return 0;
                    Object animationUtilObject = animationUtilInstance.get(null);
                    Object sourcePackage = animationUtilObject == null ? null
                            : getWindowAnimationPackage.invoke(animationUtilObject);
                    Object homeComponent = getDefaultHome.invoke(null);
                    Object homePackage = homeComponent instanceof ComponentName
                            ? ((ComponentName) homeComponent).getPackageName() : null;
                    if (sourcePackage == null || sourcePackage.equals(homePackage)) return 0;
                } catch (Throwable ignored) {
                    // 取值失败时保持原结果。
                }
                return result;
            });
        });
    }

    /**
     * 症状：第 2 张任务卡带着「聚焦」样式（菜单按钮与标题常亮）且永不回落。
     *
     * 根因：OplusRecentsViewImpl.updateCurveProperties() 对「满足自动聚焦条件的第 2 张卡」用的是
     * getChildStart(v)（该卡的应有槽位），而不是其他卡用的 getChildStartWithTranslation(v)
     * （真实位置）。从主屏进入概览时 mCurrentPage = 0，第 2 张卡的槽位恰好等于屏幕中心，于是它每轮
     * 都被算出插值 0——「我正处在屏幕中间」。
     *
     * 处置：在每张卡进入 onPageScroll 之前，用原厂同一个 ScrollState.updateInterpolation 公式、以
     * 真实位置重算一次插值。公式、曲线与 alpha / scale / dim 仍全部由原厂负责，本方法只纠正输入；
     * 常态分支本来就取这个值，重算不产生差异。
     */
    private static void fixCardInsertionInterpolation(HookScope scope) {
        scope.install("card-interpolation-source", () -> {
            Class<?> observer = scope.type(Targets.OVERVIEW_OBSERVER);
            Class<?> taskView = scope.type(Targets.OP_TASK_VIEW);
            Class<?> baseTaskView = scope.type(Targets.TASK_VIEW);
            Class<?> stackPagedView = scope.type(Targets.STACK_PAGED_VIEW);
            Class<?> recentsView = scope.type(Targets.RECENTS_VIEW);
            Class<?> scrollState = scope.type(Targets.SCROLL_STATE);
            Class<?> orientationHandler = scope.type(Targets.PAGED_ORIENTATION_HANDLER);
            Class<?> baseActivity = scope.type(Targets.BASE_ACTIVITY);
            Class<?> deviceProfile = scope.type(Targets.DEVICE_PROFILE);

            Method otherDesk = scope.otherDesk();
            Method onPageScroll = scope.method(taskView, "onPageScroll", scrollState, boolean.class);
            Method getRecentsView = scope.method(baseTaskView, "getRecentsView");
            Method getChildStartWithTranslation = scope.method(orientationHandler,
                    "getChildStartWithTranslation", View.class);
            Method updateInterpolation = scope.method(scrollState, "updateInterpolation",
                    deviceProfile, float.class);
            Method getDeviceProfile = scope.method(baseActivity, "getDeviceProfile");
            Field orientationHandlerField = scope.field(stackPagedView, "mOrientationHandler");
            Field activityField = scope.field(recentsView, "mActivity");

            scope.hook(onPageScroll).intercept(chain -> {
                try {
                    Object taskViewInstance = chain.getThisObject();
                    Object state = chain.getArg(0);
                    if (taskViewInstance instanceof View && state != null
                            && scope.isThirdPartyHome(otherDesk)) {
                        Object recents = getRecentsView.invoke(taskViewInstance);
                        if (recents != null) {
                            Object handler = orientationHandlerField.get(recents);
                            Object activity = activityField.get(recents);
                            if (handler != null && activity != null) {
                                float realStart = (Float) getChildStartWithTranslation.invoke(
                                        handler, taskViewInstance);
                                updateInterpolation.invoke(state,
                                        getDeviceProfile.invoke(activity), realStart);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 取值失败时保持原厂输入（原行为）。
                }
                return chain.proceed();
            });
        });
    }
}
