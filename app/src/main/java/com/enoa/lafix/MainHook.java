package com.enoa.lafix;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LaFix —— ColorOS 系统桌面在「第三方桌面被设为默认 Home」时的兼容修复。
 *
 * ColorOS 把「最近任务 / 转场 / 返回桌面」整套私有实现都挂在“本桌面是否为系统默认 Home”
 * 这一个判断上（OverviewComponentObserver.isOtherDesk()）。一旦第三方桌面成为默认
 * Home，这些私有路径被判为“不支持”，派生出两类问题：
 *
 * 返回桌面：Overview 改走 Fallback 路径。Home 图层只更新 matrix/alpha 而不
 * setShow()；返回动画又把 Home 图层当成“退出应用”一起淡出；按 Home 还会启动
 * 一段“模拟退出动画”，在它的 recents 动画结束前（实测约 0.9s）接管整屏触摸与导航栏按键。
 *
 * 任务按钮：任务键被退化到冷启动独立 Activity；连点守卫、以及“Home+菜单同击”
 * 守卫又会各自吞掉一次按键。
 */
public final class MainHook extends XposedModule {

    private static final String TAG = "LaFix";
    private static final String LAUNCHER_PKG = "com.android.launcher";

    private static final String OBSERVER = "com.android.quickstep.OverviewComponentObserver";
    private static final String SERVICE = "com.android.quickstep.OplusBaseTouchInteractionService";

    /** 是否输出模块日志。默认关闭，调试时改为 true 重新构建。 */
    private static final boolean DEBUG_LOG = false;

    // ─────────────────────────────────────────────────────────────
    // 模块入口
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log("module loaded, api=" + getApiVersion() + ", framework=" + getFrameworkName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (pkg == null || !pkg.startsWith(LAUNCHER_PKG)) return;

        ClassLoader cl = param.getDefaultClassLoader();
        log("attach " + pkg);
        if (cl == null) {
            log("no classloader, nothing hooked");
            return;
        }

        installHomeHooks(cl);
        installTaskHooks(cl);
        log("install done");
    }

    // ─────────────────────────────────────────────────────────────
    // 返回桌面
    // ─────────────────────────────────────────────────────────────

    private void installHomeHooks(ClassLoader cl) {
        installSurfaceShowHook(cl);
        installHomeTargetHook(cl);
        installHomeKeyHook(cl);
    }

    /**
     * 返回桌面时只有壁纸、动画结束才闪出内容：Fallback 的 {@code setHomeScaleAndAlpha()}
     * 只更新 matrix/alpha、漏了 {@code setShow()}（父类 {@code onTasksAppeared()} 又提前返回、
     * 跳过了本该有的 {@code setAlpha(1).setShow()}）。这里在同一笔 SurfaceControl 事务上补一次
     * {@code setShow()}，不改时长/透明度/缩放/层级，也不另建事务。
     */
    private void installSurfaceShowHook(ClassLoader cl) {
        install("surface", () -> {
            Class<?> handler = Class.forName(
                    "com.android.quickstep.FallbackSwipeHandler", false, cl);
            Class<?> properties = Class.forName(
                    "com.android.quickstep.util.SurfaceTransaction$SurfaceProperties", false, cl);
            Class<?> target = Class.forName(
                    "com.android.systemui.shared.system.RemoteAnimationTargetCompat", false, cl);

            Method show = properties.getDeclaredMethod("setShow");
            show.setAccessible(true);
            Method transform = handler.getDeclaredMethod(
                    "setHomeScaleAndAlpha", properties, target, float.class, float.class);
            transform.setAccessible(true);

            hook(transform).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    show.invoke(chain.getArg(0)); // 与原更新同一笔事务；图层无效时 setShow 自身会失败
                } catch (Throwable ignore) {
                    // 保持原行为，不打断动画。
                }
                return result;
            });
        });
    }

    /**
     * 从半透明页面返回时 Home 图层被当成退出应用一起缩小淡出：{@code RectTransformHelper.isAppTarget()}
     * 只看 mode，{@code CLOSING} 目标里混入了 {@code activityType=HOME} 的第三方 Home 图层。
     * 仅在 SWIPE_TO_HOME + 第三方 Home 时改判为非应用目标。
     */
    private void installHomeTargetHook(ClassLoader cl) {
        install("home-target", () -> {
            Class<?> helper = Class.forName(
                    "com.android.quickstep.util.animation.RectTransformHelper", false, cl);
            Class<?> target = Class.forName(
                    "com.android.systemui.shared.system.RemoteAnimationTargetCompat", false, cl);
            Class<?> observer = Class.forName(OBSERVER, false, cl);

            Method isAppTarget = helper.getDeclaredMethod("isAppTarget", int.class, target);
            isAppTarget.setAccessible(true);
            Method isOtherDesk = method(observer, "isOtherDesk");
            Field animType = field(helper, "mAnimType");
            Field activityType = field(target, "activityType");

            hook(isAppTarget).intercept(chain -> {
                Object result = chain.proceed();
                if (!Boolean.TRUE.equals(result)) return result;
                try {
                    if (activityType.getInt(chain.getArg(1)) == 2 // TARGET_TYPE_HOME
                            && "SWIPE_TO_HOME".contentEquals(
                                    String.valueOf(animType.get(chain.getThisObject())))
                            && isOtherDesk(isOtherDesk)) {
                        return Boolean.FALSE;
                    }
                } catch (Throwable ignore) {
                    // 判定失败时保持原结果。
                }
                return result;
            });
        });
    }

    /**
     * 按 Home 后整屏输入被接管约 0.9s：三键模式下 {@code simulateUpSlide(true)} 会启动一段
     * “模拟退出动画”，其内部跑一次 recents 动画，并在结束前打开 launcher 输入消费者、开启导航栏
     * 按键拦截（fallback 路径不会提前释放，只能等控制器 finish）。
     *
     * 做法：满足条件时直接派发系统原本的 Home 意图并返回 {@code null}，从源头不产生这段动画；
     * 第三方桌面仍会收到正常的 {@code onNewIntent}（Home 预览、返回主屏、关闭文件夹等动作不受影响）。
     * 「已在桌面」与「从应用回到桌面」两种情形都覆盖，任何判定失败都回落到原实现。
     */
    private void installHomeKeyHook(ClassLoader cl) {
        install("home-key", () -> {
            Class<?> service = Class.forName(SERVICE, false, cl);
            Class<?> observer = Class.forName(OBSERVER, false, cl);
            Class<?> device = Class.forName(
                    "com.android.quickstep.RecentsAnimationDeviceState", false, cl);
            Class<?> animMgr = Class.forName(
                    "com.android.quickstep.TaskAnimationManager", false, cl);

            Method simulate = method(service, "simulateUpSlide", boolean.class);
            Method isSame = method(observer, "isHomeAndOverviewSame");
            Method getHomeIntent = method(observer, "getHomeIntent");
            Method isButtonNav = method(device, "isButtonNavMode");
            Method isAnimRunning = method(animMgr, "isRecentsAnimationRunning");
            Method getDevice = method(service, "getMDeviceState");
            Method getAnimMgr = method(service, "getMTaskAnimationManager");
            Field observerField = field(service, "mOverviewComponentObserver");

            hook(simulate).intercept(chain -> {
                if (!Boolean.TRUE.equals(chain.getArg(0))) return chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    Context context = (Context) self;
                    KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
                    if (keyguard == null || keyguard.isKeyguardLocked()) return chain.proceed();

                    Object obs = observerField.get(self);
                    if (obs == null) return chain.proceed(); // 服务尚未初始化完
                    if (Boolean.TRUE.equals(isSame.invoke(obs))) return chain.proceed();
                    if (!Boolean.TRUE.equals(isButtonNav.invoke(getDevice.invoke(self)))) {
                        return chain.proceed();
                    }
                    if (Boolean.TRUE.equals(isAnimRunning.invoke(getAnimMgr.invoke(self)))) {
                        return chain.proceed(); // 有在途最近任务动画时交还原逻辑
                    }

                    Intent home = (Intent) getHomeIntent.invoke(obs);
                    if (home == null) return chain.proceed();
                    ComponentName component = home.getComponent();
                    if (component == null
                            || LAUNCHER_PKG.equals(component.getPackageName())) {
                        return chain.proceed();
                    }

                    context.startActivity(new Intent(home));
                    return null; // 跳过模拟退出动画（连同它带来的触摸/按键接管）
                } catch (Throwable t) {
                    return chain.proceed(); // 任何判定失败都回到原实现
                }
            });
        });
    }

    // ─────────────────────────────────────────────────────────────
    // 任务按钮
    // ─────────────────────────────────────────────────────────────

    private void installTaskHooks(ClassLoader cl) {
        // 统一开关：任务键回到命令队列派发路径，而不是冷启动独立 RecentsActivity。
        hookOtherDeskBoolean(cl, "com.android.common.util.AppFeatureUtils",
                "isSupportMultiTapMenu", true, "AppFeatureUtils#isSupportMultiTapMenu");

        installRecentsViewGuardHook(cl);

        // 放宽 showNextTask() 的「同一任务 + 距上次启动 <1s」拒绝守卫，避免连点被吞。
        hookOtherDeskBoolean(cl, "com.oplus.quickstep.utils.RecentsViewAnimUtil",
                "checkLaunchTaskLongestTimeGap", true,
                "RecentsViewAnimUtil#checkLaunchTaskLongestTimeGap");

        // 取消「Home 键与菜单键同击」吞键：按 Home 后 0.25s 内点任务键不应被静默丢弃。
        hookOtherDeskBoolean(cl, "com.oplus.quickstep.utils.RecentsViewAnimUtil",
                "isHomeKeyMenuKeyTogetherClick", false,
                "RecentsViewAnimUtil#isHomeKeyMenuKeyTogetherClick");
    }

    /**
     * {@code getRecentsView()} 把当前 Activity 无条件强转为 {@code Launcher}，而第三方桌面下
     * 承载最近任务的是独立的 {@code RecentsActivity}（与 {@code Launcher} 是兄弟类），强转会抛
     * {@code ClassCastException} 击垮手势服务进程。这里改为安全取值：直接从
     * {@code Activity.getOverviewPanel()} 取视图（即 FallbackRecentsView，本身即
     * {@code OplusRecentsViewImpl}）；取不到则返回 {@code null}，派发点安全回退到冷启动。
     */
    private void installRecentsViewGuardHook(ClassLoader cl) {
        install("recents-view", () -> {
            Class<?> service = Class.forName(SERVICE, false, cl);
            Class<?> observer = Class.forName(OBSERVER, false, cl);
            Class<?> activityInterface = Class.forName(
                    "com.android.quickstep.BaseActivityInterface", false, cl);
            Class<?> baseActivity = Class.forName(
                    "com.android.launcher3.BaseDraggingActivity", false, cl);
            Class<?> recentsView = Class.forName(
                    "com.android.quickstep.views.OplusRecentsViewImpl", false, cl);

            Method isOtherDesk = method(observer, "isOtherDesk");
            Method getActivityInterface = method(observer, "getActivityInterface");
            Method getCreatedActivity = method(activityInterface, "getCreatedActivity");
            Method getOverviewPanel = method(baseActivity, "getOverviewPanel");
            Field observerField = field(service, "mOverviewComponentObserver");

            hook(method(service, "getRecentsView")).intercept(chain -> {
                if (!isOtherDesk(isOtherDesk)) return chain.proceed();
                try {
                    Object obs = observerField.get(chain.getThisObject());
                    if (obs == null) return null;
                    Object iface = getActivityInterface.invoke(obs);
                    if (iface == null) return null;
                    Object activity = getCreatedActivity.invoke(iface);
                    if (activity == null) return null;
                    if (((Activity) activity).isDestroyed()) return null;
                    Object panel = getOverviewPanel.invoke(activity);
                    return recentsView.isInstance(panel) ? panel : null;
                } catch (Throwable ignore) {
                    // 任何取值失败都返回 null，让派发点安全回退到冷启动。
                    return null;
                }
            });
        });
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * 让某个无参布尔方法在「第三方桌面」下固定返回 {@code value}，其余情况保持原实现。
     * 用于把 ColorOS 挂在“是否默认 Home”上的几个开关统一到正常分支。
     */
    private void hookOtherDeskBoolean(ClassLoader cl, String className, String methodName,
                                      boolean value, String label) {
        install(label, () -> {
            Class<?> observer = Class.forName(OBSERVER, false, cl);
            Method isOtherDesk = method(observer, "isOtherDesk");
            hook(method(Class.forName(className, false, cl), methodName)).intercept(chain -> {
                if (isOtherDesk(isOtherDesk)) return Boolean.valueOf(value);
                return chain.proceed();
            });
        });
    }

    /** 统一 hook 安装的异常兜底：命中失败只记日志、保持原行为。 */
    private void install(String label, HookInstaller installer) {
        try {
            installer.install();
            log("hooked " + label);
        } catch (Throwable t) {
            log(label + " hook unsupported, keep original (" + t + ")");
        }
    }

    private interface HookInstaller {
        void install() throws Throwable;
    }

    private static boolean isOtherDesk(Method isOtherDesk) {
        try {
            return Boolean.TRUE.equals(isOtherDesk.invoke(null));
        } catch (Throwable t) {
            return false; // 判定失败时按“是默认 Home”处理，保持原行为。
        }
    }

    private static Method method(Class<?> c, String name, Class<?>... params) throws Exception {
        try {
            return c.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        }
    }

    private static Field field(Class<?> c, String name) throws Exception {
        try {
            return c.getField(name);
        } catch (NoSuchFieldException e) {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
    }

    private void log(String message) {
        if (!DEBUG_LOG) return;
        try {
            Log.i(TAG, message);
        } catch (Throwable ignore) {
        }
        try {
            log(Log.INFO, TAG, message);
        } catch (Throwable ignore) {
        }
    }
}
