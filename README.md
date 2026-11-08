# LaFix

ColorOS 16 将第三方 Launcher 设为默认 Home 后，系统桌面会回退到备用的任务导航动画模式。

早期尚可兼容，如今兼容性问题愈发严重，传闻 ColorOS 17 也未解决，由此而生该模块。

LaFix 是一个 libxposed 模块，专门修正**虚拟按键导航模式**下第三方桌面启动器的兼容问题。

手势导航模式似乎仅 Home 键存在问题，社区已有相关解决方案。

（堆叠模式的非预期平铺问题未在计划中）

## 开发对照的版本

| 项目 | 版本 |
| --- | --- |
| 系统 | ColorOS 16（Android 16） |
| 系统桌面 | `com.android.launcher` 16.4.5，versionCode 160040005 |
| 智能侧边栏 | `com.coloros.smartsidebar` 16.16.2 |
| 框架 | LSPosed 2.2.0，libxposed API 101 / 102 |
| 导航方式 | 仅虚拟按键 |

## 各作用域的处理

### 系统桌面

问题的根源是 ColorOS 把「最近任务 / 转场」的整套私有实现挂在 `OverviewComponentObserver.isOtherDesk()`
（本桌面是否为系统默认 Home）这一个判定上。第三方桌面成为默认 Home 之后，这些路径被整体判为不
支持，于是出现路径不一致、守卫失效、回调缺失。

修复主线是把这一判定在第三方桌面下统一到「默认 Home」分支，具体做法是只翻转转发层
`OplusStackRecentsConfig.isOtherDesk()`，不动 `OverviewComponentObserver` 的真定义——后者会把
「本桌面是否为默认 Home」全线翻转，连 Home 键的目标 Intent 选取都会出错。

按症状对应的修复：

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| 返回桌面先只看到壁纸 | fallback 的 `setHomeScaleAndAlpha()` 只更新 matrix / alpha，漏了可见性 | 在同一笔 SurfaceControl 事务上补一次 `setShow()` |
| 从半透明页面返回时桌面跟着应用缩小淡出 | `RectTransformHelper.isAppTarget()` 只看 mode，把 `ACTIVITY_TYPE_HOME` 图层当成退出应用 | 仅 `SWIPE_TO_HOME` + 第三方 Home 时改判为非应用目标 |
| 三键导航下 Home 后约 0.9 秒内整屏无响应 | `simulateUpSlide(true)` 启动模拟退出动画，内部跑 recents 动画并接管触摸与导航按键 | 条件满足时直接派发系统原有的 Home Intent，从源头不产生这段动画 |
| 任务键偶发无响应、手势服务进程被杀 | `getRecentsView()` 把当前 Activity 无条件强转为 `Launcher`，fallback 下抛 `ClassCastException` | 第三方桌面下改走 `getActivityInterface().getCreatedActivity().getOverviewPanel()`，取不到返回 null |
| 应用内打开概览后触摸被接管、动画重放 | 命令队列派发以「概览由 Launcher 自身承载」为前提，与独立 `RecentsActivity` 混用 | 按「概览是否已在前台」分流：已在前台走命令队列，未展开走冷启动 |
| 连按任务键没反应 | 冷启动路径在进入动画期间把概览判为不可见，按键被双按守卫吞掉 | 概览已在前台即交回命令队列，它自带 500ms 合并与动画互斥守卫 |
| 概览里按 Home 直接闪烁跳桌面 | 原实现靠 `forceToHome(true)` 播「概览滑出」动画，直接改派 Home Intent 会跳过这段动画 | 概览在前台时放行原实现，仅在概览未展开时接管 |
| 概览里按返回回到桌面 | `RecentsActivity.onBackPressed()` 被覆写为 `startHome()`，盖掉了父类「有运行任务就启动它」 | 第三方桌面下改回父类语义 |
| 进入概览时聚焦的卡与跳转到的任务不一致 | 跳转取第 1 张，聚焦取 `currentPage + 1`，从主屏进入时基准不同 | 把聚焦页改为运行任务所在页 |
| 第 2 张任务卡「聚焦」样式常亮 | `updateCurveProperties()` 对该卡用了应有槽位而非真实位置算插值 | 用原厂同一公式、以真实位置重算输入 |
| 进入概览缺少与默认桌面一致的淡入 | `isDefaultHome()` 决定 home / 壁纸层起始透明度 | 第三方桌面下固定为 true |
| 转场卡住后无法自愈 | 1000ms 兜底只对真 Launcher 生效 | 在兜底中追加 fallback 概览的状态恢复 |

实现细节见 [`docs/reverse-engineering.md`](docs/reverse-engineering.md)、
[`docs/recents-third-party-vs-stock.md`](docs/recents-third-party-vs-stock.md)、
[`docs/recents-open-from-app.md`](docs/recents-open-from-app.md)、
[`docs/overview-navigation.md`](docs/overview-navigation.md)、
[`docs/recents-animation-parity.md`](docs/recents-animation-parity.md)。

### 智能侧边栏（最近活动应用）

侧边栏的最近应用由 `RecentAppSource` 从 UsageStatsManager 近 24 小时的聚合用量推导。第三方桌面
成为默认 Home 后，每次回主屏、开设置都会把它记为「最近使用」，于是它长期占住一格——既没用，
又挤掉真正想回去的应用。

LaFix 在最近数据的两个出口（`requestRecentAppDataFromService()`、`requestRecentAppDataFromDefault()`）
返回后，按 `EntryBean.getPkg()` 剔除当前默认 Home 的包名。默认 Home 是系统桌面时不做处理——此时
列表里的第三方桌面只是普通应用，可以正常跳转。

分析与证据见 [`docs/sidebar-recents.md`](docs/sidebar-recents.md)。

## 应用启动确认对话框

将第三方桌面启动器设为默认桌面后，每次点击图标打开应用，系统都会弹出「XXX 想要打开 YYY」的确认对话框，且仅提供「仅此一次」与「允许 30 天」两个选项，无法永久放行。尽管社区中有不少允许永久放行的解决方案，但每次启动应用仍需额外处理一次对话框，对第三方桌面的日常使用体验影响较大。

该问题已纳入计划，理论上应将默认桌面启动器应用加入权限监控白名单，使其跳转应用时不再进行询问。

如果你和我一样，本来就对每次跳转应用都要询问感到厌烦，可以直接全局关闭打开应用对话框。

1. **关闭权限监控（图形界面）**：进入「设置 → 系统设置 → 开发者选项」，启用「禁止权限监控」。代价是应用权限设置界面会退化为精简状态，部分权限管理入口缺失。

2. **关闭启动确认（adb）**：执行

   ```bash
   adb shell settings put global app_start_confirm_rus_enable 0
   ```

如需恢复默认行为，将取值改回 `1` 即可。

## 致谢

home 导航的问题分析与修复思路参考了 [Home Visibility Fix](https://github.com/xieincz/Home_Visibility_Fix)。
