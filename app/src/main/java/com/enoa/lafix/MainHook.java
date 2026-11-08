package com.enoa.lafix;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口。静态作用域见 scope.list，按包名分派到对应修复组：
 *
 * - com.android.launcher（系统桌面）：返回桌面壁纸空窗、Home 后短暂失去响应、任务键派发路径错配、
 *   概览转场动画缺失或卡死；
 * - com.coloros.smartsidebar（智能侧边栏）：默认桌面被记入「最近活动应用」。
 *
 * 两者共享同一个前提——第三方桌面被设为默认 Home。系统桌面进程内用 OverviewComponentObserver
 * .isOtherDesk() 判定；智能侧边栏进程内没有这套私有实现，改用 ThirdPartyHome 走平台口径。
 *
 * 所有 Hook 都通过反射定位，目标缺失只记日志并跳过，不影响其余修复。安装结果可用
 * adb logcat -s LaFix 查看（hooked: ... / ... unsupported, skipped）。
 */
public final class MainHook extends XposedModule {

    /** 日志 TAG，同时用于 adb logcat -s LaFix 过滤。 */
    static final String TAG = "LaFix";

    /** 系统桌面包名；同时是模块作用范围与「本桌面」判定的基准。 */
    static final String LAUNCHER_PACKAGE = "com.android.launcher";

    /** 智能侧边栏包名：承载最近活动应用列表。 */
    static final String SIDEBAR_PACKAGE = "com.coloros.smartsidebar";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Log.i(TAG, "module loaded, api=" + getApiVersion() + ", framework=" + getFrameworkName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String packageName = param.getPackageName();
        ClassLoader classLoader = param.getDefaultClassLoader();
        if (classLoader == null) {
            Log.w(TAG, "no classloader for " + packageName + ", nothing hooked");
            return;
        }

        HookScope scope = new HookScope(this, classLoader);
        if (LAUNCHER_PACKAGE.equals(packageName)) {
            HomeFixes.install(scope);
            RecentsKeyFixes.install(scope);
            RecentsAnimationFixes.install(scope);
            TransitionFixes.install(scope);
        } else if (SIDEBAR_PACKAGE.equals(packageName)) {
            SidebarRecentsFixes.install(scope);
        } else {
            return;
        }
        Log.i(TAG, "install finished: " + packageName);
    }
}
