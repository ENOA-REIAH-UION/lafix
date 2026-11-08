package com.enoa.lafix;

/**
 * 「按任务键进入最近任务」的窗口动画对齐（作用范围：系统桌面）。
 *
 * AppToOverviewAnimationProvider.createWindowAnimation() 用 OverviewComponentObserver.isDefaultHome()
 * 决定 home / 壁纸层的起始透明度：true 取 1.0（常驻），false 取 0.0（淡入）。fallback 因此多出
 * 一次淡入，第三方桌面下把它固定为 true 即可与默认 Home 一致。
 *
 * 其余消费者不受影响：概览目标 Intent 读的是字段 mIsDefaultHome，不经过这个方法。
 *
 * 已知且不在本模块范围内的缺口：桌面与壁纸的整体缩放、景深由 Launcher 侧的 OplusDepthController
 * 驱动，RecentsActivity 没有对应宿主，fallback 侧不存在该动画（分析见 docs/recents-animation-parity.md）。
 */
final class RecentsAnimationFixes {

    private RecentsAnimationFixes() {
    }

    static void install(HookScope scope) {
        scope.forceBooleanWhenThirdPartyHome(Targets.OVERVIEW_OBSERVER, "isDefaultHome", true,
                "OverviewComponentObserver#isDefaultHome");
    }
}
