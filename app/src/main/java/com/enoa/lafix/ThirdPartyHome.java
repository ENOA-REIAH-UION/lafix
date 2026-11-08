package com.enoa.lafix;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/**
 * 「默认 Home 是否为第三方桌面」的判定（智能侧边栏进程内使用）。
 *
 * 系统桌面进程内，同等语义由 OverviewComponentObserver.isOtherDesk() 给出；智能侧边栏进程没有
 * 这套私有实现，于是退回平台口径：解析 ACTION_MAIN + CATEGORY_HOME 的默认结果，只要默认 Home
 * 存在且不是系统桌面，就按第三方桌面处理，与 isOtherDesk() 保持同一门控。
 *
 * 解析失败（Context 为空、没有默认 Home 等）一律按「非第三方桌面」处理，即不介入。
 */
final class ThirdPartyHome {

    private ThirdPartyHome() {
    }

    /** 当前默认 Home 的包名；解析不到时返回 null。 */
    static String defaultHomePackage(Context context) {
        if (context == null) return null;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = context.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (info == null || info.activityInfo == null) return null;
            return info.activityInfo.packageName;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 默认 Home 存在且不是系统桌面时返回其包名，否则返回 null。 */
    static String thirdPartyHomePackage(Context context) {
        String home = defaultHomePackage(context);
        if (home == null || MainHook.LAUNCHER_PACKAGE.equals(home)) return null;
        return home;
    }

    /** 默认 Home 为第三方桌面时返回 true。 */
    static boolean isThirdPartyHome(Context context) {
        return thirdPartyHomePackage(context) != null;
    }
}
