package com.enoa.lafix;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 「返回桌面」相关修复（作用范围：系统桌面）。
 *
 * 覆盖三个症状：返回桌面先看到纯壁纸、Home 后约 0.9 秒内整屏无响应、从半透明页面返回时桌面图层
 * 跟随前台应用一起缩小淡出。
 */
final class HomeFixes {

    /** RemoteAnimationTargetCompat.ACTIVITY_TYPE_HOME。 */
    private static final int ACTIVITY_TYPE_HOME = 2;

    private HomeFixes() {
    }

    static void install(HookScope scope) {
        fixHomeLayerVisibility(scope);
        fixHomeLayerTreatedAsClosingApp(scope);
        dispatchHomeIntentWithoutSimulatedExit(scope);
    }

    /**
     * 症状：按 Home 或手势返回桌面时先显示纯壁纸，动画结束后内容才突然出现。
     *
     * 根因：FallbackSwipeHandler.setHomeScaleAndAlpha() 只更新 matrix / alpha，漏了可见性；父类
     * onTasksAppeared() 又提前返回，跳过了本该有的 setAlpha(1).setShow()。结果是 Home 图层已就位
     * 却不可见。
     *
     * 处置：在原调用的同一笔 SurfaceControl 事务上补一次 setShow()，不改时长 / 透明度 / 缩放 / 层级，
     * 也不另建事务。
     */
    private static void fixHomeLayerVisibility(HookScope scope) {
        scope.install("home-layer-visibility", () -> {
            Class<?> handler = scope.type(Targets.FALLBACK_SWIPE_HANDLER);
            Class<?> surfaceProperties = scope.type(Targets.SURFACE_PROPERTIES);
            Class<?> animationTarget = scope.type(Targets.REMOTE_ANIMATION_TARGET);

            Method setShow = scope.method(surfaceProperties, "setShow");
            Method setHomeScaleAndAlpha = scope.method(handler, "setHomeScaleAndAlpha",
                    surfaceProperties, animationTarget, float.class, float.class);

            scope.hook(setHomeScaleAndAlpha).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    setShow.invoke(chain.getArg(0));
                } catch (Throwable ignored) {
                    // 图层无效时 setShow 自身会失败；保持原行为，不打断动画。
                }
                return result;
            });
        });
    }

    /**
     * 症状：从 LSPosed 管理器等半透明 Activity 返回时，底层桌面被当成退出中的应用一起缩小淡出。
     *
     * 根因：RectTransformHelper.isAppTarget() 只看 mode，CLOSING 目标里混入了
     * activityType == ACTIVITY_TYPE_HOME 的第三方 Home 图层。
     *
     * 处置：仅在「SWIPE_TO_HOME + 第三方 Home」这一组合下把结果改判为非应用目标。
     */
    private static void fixHomeLayerTreatedAsClosingApp(HookScope scope) {
        scope.install("home-layer-not-closing-app", () -> {
            Class<?> helper = scope.type(Targets.RECT_TRANSFORM_HELPER);
            Class<?> animationTarget = scope.type(Targets.REMOTE_ANIMATION_TARGET);
            Method otherDesk = scope.otherDesk();

            Method isAppTarget = scope.method(helper, "isAppTarget", int.class, animationTarget);
            Field animType = scope.field(helper, "mAnimType");
            Field activityType = scope.field(animationTarget, "activityType");

            scope.hook(isAppTarget).intercept(chain -> {
                Object result = chain.proceed();
                if (!Boolean.TRUE.equals(result)) return result;
                try {
                    boolean isHomeLayer =
                            activityType.getInt(chain.getArg(1)) == ACTIVITY_TYPE_HOME;
                    boolean isSwipeToHome = "SWIPE_TO_HOME"
                            .contentEquals(String.valueOf(animType.get(chain.getThisObject())));
                    if (isHomeLayer && isSwipeToHome && scope.isThirdPartyHome(otherDesk)) {
                        return Boolean.FALSE;
                    }
                } catch (Throwable ignored) {
                    // 判定失败时保持原结果。
                }
                return result;
            });
        });
    }

    /**
     * 症状：三键导航下按 Home 后约 0.9 秒内，导航栏按键与桌面图标都无响应。
     *
     * 根因：simulateUpSlide(true) 会启动一段模拟退出动画，内部跑一次 recents 动画，并在结束前打开
     * launcher 输入消费者、开启导航栏按键拦截；fallback 路径不会提前释放，只能等控制器 finish。
     *
     * 处置：条件满足时直接派发系统原本的 Home 意图并返回 null，从源头不产生这段动画。第三方桌面
     * 仍会收到正常的 onNewIntent，Home 预览、返回主屏、关闭文件夹等动作不受影响。
     *
     * 概览在前台时必须放行：Home 键被 notifyInterceptHomeKeyEvent 拦截后调用 simulateUpSlide(true)，
     * 原实现会新建 OplusOtherActivityInputConsumer、startWindowAnimation(true) 并 forceToHome(true)，
     * 由此驱动平台自己的「滑回桌面」转场（概览侧另有 RecentsActivity.startHome() 的
     * StartHomeFromRecents 远程动画负责把 Home 窗口拉起来）。模块若在此直接派发裸 Home 意图，就没
     * 有了这层转场，窗口被直接切换，表现为屏幕闪烁。因此仅在概览既未展开也未进入时接管；这同时
     * 覆盖「用任务键打开概览的动画途中按 Home」——交给原实现打断当前动画并重播「滑回桌面」。
     *
     * Home 键与任务键的同击保护保持原厂行为、本模块不干预：上述模拟动画被跳过后对应的状态也不会
     * 被置位，任务列表动画进行中的按键仍由原厂守卫接住。
     */
    private static void dispatchHomeIntentWithoutSimulatedExit(HookScope scope) {
        scope.install("home-key-dispatch", () -> {
            Class<?> service = scope.type(Targets.TOUCH_SERVICE);
            Class<?> observer = scope.type(Targets.OVERVIEW_OBSERVER);
            Class<?> deviceState = scope.type(Targets.DEVICE_STATE);
            Class<?> animationManager = scope.type(Targets.TASK_ANIMATION_MANAGER);
            RecentsOverview overview = RecentsOverview.resolve(scope);

            Method simulateUpSlide = scope.method(service, "simulateUpSlide", boolean.class);
            Method isHomeAndOverviewSame = scope.method(observer, "isHomeAndOverviewSame");
            Method getHomeIntent = scope.method(observer, "getHomeIntent");
            Method isButtonNavMode = scope.method(deviceState, "isButtonNavMode");
            Method isRecentsAnimationRunning =
                    scope.method(animationManager, "isRecentsAnimationRunning");
            Method getDeviceState = scope.method(service, "getMDeviceState");
            Method getAnimationManager = scope.method(service, "getMTaskAnimationManager");
            Field observerField = scope.field(service, "mOverviewComponentObserver");

            scope.hook(simulateUpSlide).intercept(chain -> {
                if (!Boolean.TRUE.equals(chain.getArg(0))) return chain.proceed();

                Object serviceInstance = chain.getThisObject();
                Intent homeIntent = null;
                try {
                    KeyguardManager keyguardManager =
                            ((Context) serviceInstance).getSystemService(KeyguardManager.class);
                    Object observerInstance = observerField.get(serviceInstance);

                    // 仅当「三键导航 + 概览由独立 Activity 承载 + 概览未在前台 + 无在途动画」时接管；
                    // 目标 Intent 仍须指向第三方桌面（组件为空或仍是本桌面则不接管）。
                    if (keyguardManager != null && !keyguardManager.isKeyguardLocked()
                            && overview.showing() == null
                            && observerInstance != null
                            && !Boolean.TRUE.equals(
                                    isHomeAndOverviewSame.invoke(observerInstance))
                            && Boolean.TRUE.equals(isButtonNavMode.invoke(
                                    getDeviceState.invoke(serviceInstance)))
                            && !Boolean.TRUE.equals(isRecentsAnimationRunning.invoke(
                                    getAnimationManager.invoke(serviceInstance)))) {
                        Intent candidate = (Intent) getHomeIntent.invoke(observerInstance);
                        ComponentName component = candidate == null ? null : candidate.getComponent();
                        if (component != null
                                && !MainHook.LAUNCHER_PACKAGE.equals(component.getPackageName())) {
                            homeIntent = candidate;
                        }
                    }
                } catch (Throwable ignored) {
                    // 任何判定失败都保持 homeIntent == null，交还原实现。
                }
                if (homeIntent == null) return chain.proceed();

                try {
                    ((Context) serviceInstance).startActivity(new Intent(homeIntent));
                    return null; // 跳过模拟退出动画，连同它带来的触摸 / 按键接管
                } catch (Throwable ignored) {
                    return chain.proceed(); // 派发失败时交还原实现
                }
            });
        });
    }
}
