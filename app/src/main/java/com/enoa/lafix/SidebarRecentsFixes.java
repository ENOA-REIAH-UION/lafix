package com.enoa.lafix;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 智能侧边栏「最近活动应用」（作用范围：com.coloros.smartsidebar）。
 *
 * 侧边栏的最近应用由 RecentAppSource 从 UsageStatsManager 近 24 小时的聚合用量推导。第三方桌面
 * 作为默认 Home 后，每次回主屏、开设置都会把该桌面记为「最近使用」，于是它长期占住一格。
 *
 * 处置：在最近数据的两个出口上，把当前默认 Home 的包名剔除。
 *   - requestRecentAppDataFromService(boolean)：正常取数；
 *   - requestRecentAppDataFromDefault(int)：用量不足时的兜底列表。
 * 两者都返回 List&lt;EntryBean&gt;，按 getPkg() 过滤后返回新列表。仅在默认 Home 为第三方桌面时
 * 生效——默认 Home 是系统桌面时，列表里的第三方桌面只是普通应用，可正常跳转。
 */
final class SidebarRecentsFixes {

    private SidebarRecentsFixes() {
    }

    static void install(HookScope scope) {
        scope.install("sidebar-recent-drop-home", () -> {
            Class<?> source = scope.type(Targets.SIDEBAR_RECENT_SOURCE);
            Class<?> entryBean = scope.type(Targets.SIDEBAR_ENTRY_BEAN);
            Class<?> contextHolder = scope.type(Targets.SIDEBAR_CONTEXT_HOLDER);

            Field sContext = scope.field(contextHolder, "sContext");
            Method getPkg = scope.method(entryBean, "getPkg");
            Method fromService =
                    scope.method(source, "requestRecentAppDataFromService", boolean.class);
            Method fromDefault =
                    scope.method(source, "requestRecentAppDataFromDefault", int.class);

            scope.hook(fromService).intercept(chain -> dropDefaultHome(chain, sContext, getPkg));
            scope.hook(fromDefault).intercept(chain -> dropDefaultHome(chain, sContext, getPkg));
        });
    }

    /**
     * 放行原实现后，从返回的 List&lt;EntryBean&gt; 中剔除当前默认 Home。默认 Home 非第三方桌面、
     * Context 未就绪或返回值不是列表时原样返回。
     */
    private static Object dropDefaultHome(XposedInterface.Chain chain,
                                          Field sContext, Method getPkg) throws Throwable {
        Object result = chain.proceed();
        if (!(result instanceof List)) return result;

        Object context = sContext.get(null);
        String home = context instanceof Context
                ? ThirdPartyHome.thirdPartyHomePackage((Context) context) : null;
        if (home == null) return result;

        List<?> original = (List<?>) result;
        List<Object> filtered = new ArrayList<>(original.size());
        for (Object item : original) {
            String pkg = null;
            try {
                pkg = (String) getPkg.invoke(item);
            } catch (Throwable ignored) {
                // 取不到包名时保留该项，避免误删。
            }
            if (!home.equals(pkg)) filtered.add(item);
        }
        return filtered;
    }
}
