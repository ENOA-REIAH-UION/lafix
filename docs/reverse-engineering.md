# ColorOS 系统桌面 16.4.5 兼容性逆向分析

## 分析范围

- 分析对象：`com.android.launcher`（apktool 反编译工程），versionName `16.4.5` / versionCode `160040005`。
- 目标 SDK：`targetSdkVersion 36` / `minSdkVersion 33`。
- 关键属性：`sharedUserId="oppo.uid.launcher"`（系统应用，platform 签名）。
- 内置模块：`com.android.quickstep`（QuickStep / 最近任务）、`com.android.wm.shell`（WMShell 转场）。
- 原始工程：`/storage/emulated/0/MT2/apks/系统桌面_16.4.5_src/`，代码位于 `smali`、`smali_classes2` ~ `smali_classes7`。
- 引用约定：下文「文件:行」标注除特别说明外，均由本次分析直接核对反编译工程得出。

---

## 目录

- [0. 结论摘要](#0-结论摘要)
- [1. 关键类索引](#1-关键类索引)
- [2. 任务按钮完整事件链](#2-任务按钮完整事件链)
- [3. 根因：单一判定开关影响整条调用链](#3-根因单一判定开关影响整条调用链)
- [4. 崩溃隐患专项：getRecentsView() 的无条件类型转换](#4-崩溃隐患专项getrecentsview-的无条件类型转换)
- [5. 关键常量速查](#5-关键常量速查)
- [6. 症状、根因与修复对照](#6-症状根因与修复对照)
- [7. 复现与验证](#7-复现与验证)
- [8. 与默认 Home 场景的差异对照](#8-与默认-home-场景的差异对照)

---

## 0. 结论摘要

ColorOS 将「多击遍历任务 / 命令队列 / 私有转场 / 堆叠最近任务 / 全景」等一整套私有能力，
**统一挂载在「本桌面是否为系统默认 Home」这一项判定之上**（`OverviewComponentObserver.isOtherDesk()`）。

第三方桌面一旦被设为默认 Home，这些私有能力便**整体被判为「不支持」**：任务键由「私有命令队列路径」
退化为「AOSP fallback + 冷启动独立 `RecentsActivity`」；与此同时，其余私有分支并未同步关闭，
因而衍生出大量**路径不一致、守卫失效、回调缺失**的兼容性问题。由此可见，问题的根源在于系统桌面的
场景判定逻辑，而非任务按钮本身发生故障。

---

## 1. 关键类索引

| 模块 | 类（smali 路径） | 作用 |
| --- | --- | --- |
| 判定 | `smali_classes2/com/android/quickstep/OverviewComponentObserver.smali` | 判定默认 Home / 第三方桌面，决定用哪个 `ActivityInterface` |
| 开关 | `smali_classes2/com/android/common/util/AppFeatureUtils.smali` | `isSupportMultiTapMenu()` 总开关 |
| 开关 | `smali_classes5/com/oplus/quickstep/layout/stack/OplusStackRecentsConfig.smali` | `isOtherDesk()` / 堆叠最近任务 |
| 开关 | `smali_classes5/com/oplus/quickstep/layout/grid/OplusGridRecentsConfig.smali` | `isEnable()`（折叠屏展开 / 平板） |
| 按钮 | `smali_classes2/com/android/quickstep/OplusBaseTouchInteractionService.smali` | 任务键派发、手势服务主体 |
| 按钮 | `smali_classes2/com/android/quickstep/TouchInteractionService$TISBinder.smali` | SystemUI 按钮命令入口（binder） |
| 按钮 | `smali_classes2/com/android/quickstep/OplusOverviewCommandHelperImpl.smali` | 命令队列 / 多击 / TOGGLE 私有实现 |
| 按钮 | `smali_classes2/com/android/quickstep/OverviewCommandHelper.smali` | 命令基类 / 类型定义 |
| 按钮 | `smali_classes3/com/android/quickstep/views/OplusRecentsViewImpl.smali` | `showNextTask()` 逐任务跳转 + 拒绝守卫 |
| 按钮 | `smali_classes5/com/oplus/quickstep/utils/RecentsViewAnimUtil.smali` | 时间间隔常量 / 守卫实现 |
| 转场 | `smali_classes2/com/android/quickstep/RecentsActivityCommand.smali` | 第三方桌面下进最近任务的主路径 |
| 转场 | `smali_classes2/com/android/quickstep/AppToOverviewAnimationProvider.smali` | App→Overview 远程动画 + 超时 |
| 转场 | `smali_classes2/com/android/quickstep/FallbackActivityInterface.smali` | 非默认 Home 的 ActivityInterface |
| 转场 | `smali_classes5/com/oplus/quickstep/utils/AnimationController.smali` | 转场超时总控 |
| 转场 | `smali_classes2/com/android/quickstep/RecentsActivity.smali` | 独立最近任务 Activity + 超时 |
| 视图 | `smali_classes2/com/android/quickstep/fallback/FallbackRecentsView.smali` | fallback 下的最近任务视图 |
| 视图 | `smali_classes3/com/android/quickstep/views/LauncherRecentsView.smali` | 默认 Home 下的最近任务视图 |

---

## 2. 任务按钮完整事件链

> 本节梳理一次点击所触发的同步 / 异步事件流向。两个入口（系统导航栏三键、Taskbar 按钮）
> 最终都汇聚到 `OplusBaseTouchInteractionService.onOverviewToggle()`。

```
[阶段 0] 输入到达
   ├─ 系统导航栏「多任务键」(SystemUI) → ILauncherProxy.onOverviewToggle (事务 0x7) → TISBinder
   └─ Taskbar recents 按钮 → TaskbarNavButtonController.onButtonClick(0x4)

[阶段 1] TISBinder.onOverviewToggle()（@BinderThread）
   └─ interceptOnOverviewToggle() 恒返回 true → 转发到服务后立即 return
      （其后的 isScreenPinningActive / addCommand(4) 等均为死代码）

[阶段 2] OplusBaseTouchInteractionService.onOverviewToggle()（同步，约 12 个拦截点）
   ├─ Zen / 全局拖拽 / 屏幕固定 / 多任务启动中 / 省电 / 手势禁用 / 定位图标 等 → 命中即 return
   ├─ Launcher.hideKeyboard()
   ├─ EventBus.post(LocateEvent("onOverviewTogglePressed"))
   ├─ clearForceInvisibleFlag(9) / resetActiveRotation()
   ├─ ContextUtils.updateDefaultContext()
   ├─ SystemUiProxy.getIsStartingMultipleTasks()（IPC）
   ├─ TaskUtils.closeSystemWindowsAsync("recentapps")
   └─ 投递 Runnable（延时 0~300ms）→ lambda$12

[阶段 3] lambda$12 时序守卫
   └─ pending / 结束转场 / Home+Menu 同击 / 忽略 等 → 命中即 return；否则进入分派

[阶段 4] 分派（核心分岔）
   ├─ 手势导航   (!isInThreeButtonMode)        → RecentsActivityCommand.run()
   ├─ 三键 + 可中断动画                         → simulateUpSlideToOverview()
   ├─ 三键 + isSupportMultiTapMenu()==true      → addCommand(4)   ← 正常机型的路径
   └─ 其余                                      → RecentsActivityCommand.run()  ← 第三方桌面

[阶段 5A] 命令队列路径
   └─ 入队/合并 → executeCommand → launchTaskOnToggle → showNextTask（逐任务 + 守卫）→ startRecentsAnimation

[阶段 5B] 冷启动路径 RecentsActivityCommand.run()
   ├─ handleCommand / switchToRecentsIfVisible
   └─ registerAndStartActivity(RecentsActivity, 480ms) + 一串写死超时兜底
```

### 2.1 分派点（阶段 4）

分派是整条事件链的核心分支。`OplusBaseTouchInteractionService.smali` 方法
`onOverviewToggle$lambda$12$lambda$8`（`:8147-8368`）：

```smali
# 三键导航分支（:8253 起）
8282  sget-object v0, Lcom/android/common/util/AppFeatureUtils;->INSTANCE:...
8284  invoke-virtual {v0}, ...->isSupportMultiTapMenu()Z
8288  if-eqz v0, :cond_7                 # false → 冷启动
8290  invoke-virtual {p0}, ...->getRecentsView()...     # 仅 true 分支调用（有崩溃隐患，见第 4 节）
8294  if-eqz v0, :cond_7                 # 视图为空 → 冷启动
8296  ...->isRecentTasksEmpty()
8300  if-nez v0, :cond_7
8302  if-nez p1, :cond_7                 # p1 为另一种回退标志
8304  log "onOverviewToggle: use command to response menu"
8347  invoke-virtual {p0, 0x4}, ...->addCommand(I)V     # 命令队列路径
8351  :cond_7
8352  new-instance ... RecentsActivityCommand
8364  invoke-virtual ...->run()            # 冷启动独立 RecentsActivity
```

> 结论：在第三方桌面场景下，`isSupportMultiTapMenu()` 恒为 `false`，代码直接落入 `:cond_7` 走冷启动，
> **`getRecentsView()` 完全不会被调用** —— 这也解释了「第三方桌面下该崩溃隐患长期未被暴露」的原因。

### 2.2 命令队列路径关键点（阶段 5A）

- 命令类型（`OverviewCommandHelper.smali`）：`TYPE_SHOW=1`、`TYPE_SHOW_NEXT_FOCUS=2`、`TYPE_HIDE=3`、
  `TYPE_TOGGLE=4`、`TYPE_HOME=5`、`MAX_QUEUE_SIZE=3`。
- `OplusOverviewCommandHelperImpl.addCommand(CommandInfo)`：队列为空 → `executeNext()`；
  非空时仍需 `isSupportMultiTapMenu()==true` 才「只入队收集」（多击路径），否则按 500ms 阈值单发。
- 逐任务跳转 `OplusRecentsViewImpl#showNextTask()`：见第 4.2 节的 1 秒守卫。

### 2.3 冷启动路径关键点（阶段 5B）

- `FallbackActivityInterface#switchToRecentsIfVisible()`（`:692`）**恒返回 `false`**，
  因此「已可见即复用」的快路径永远失败，每次都必须走 `registerAndStartActivity` 冷启动。
- `overviewIntent` 在非默认 Home 下指向 `com.android.quickstep.RecentsActivity`
  （`AndroidManifest.xml` 中确实存在该 Activity）。

---

## 3. 根因：单一判定开关影响整条调用链

```
第三方桌面设为默认 Home
        │
        ▼
OverviewComponentObserver.isOtherDesk() == true
   （:559，= getPkgName() != "com.android.launcher"；pkgName 为空时 = !sIsDefaultHome）
        │
        ├─► AppFeatureUtils.isSupportMultiTapMenu() == false
        │       （:10442，= (!OplusGridRecentsConfig.isEnable()) && (!OplusStackRecentsConfig.isOtherDesk())）
        │       └─ OplusStackRecentsConfig.isOtherDesk()（:221，@JvmStatic）直接委托上面的判断
        │
        ├─► OverviewComponentObserver.isHomeAndOverviewSame() == false
        │       → 改用 FallbackActivityInterface + mOverviewIntent = mFallbackIntent(RecentsActivity)
        │
        └─► 写入 Settings.Secure.panoramic_forced_disable_state = "1"（:940-967）
```

### 3.1 开关实现（本条已逐行核对）

`AppFeatureUtils.smali:10442`：

```smali
.method public final isSupportMultiTapMenu()Z      # 注意：实例方法（调用方为 INSTANCE）
    .locals 0
    invoke-static {}, Lcom/oplus/quickstep/layout/grid/OplusGridRecentsConfig;->isEnable()Z
    move-result p0
    if-nez p0, :cond_0
    invoke-static {}, Lcom/oplus/quickstep/layout/stack/OplusStackRecentsConfig;->isOtherDesk()Z
    move-result p0
    if-nez p0, :cond_0
    const/4 p0, 0x1        # !grid && !otherDesk → true
    goto :goto_0
    :cond_0
    const/4 p0, 0x0
    :goto_0
    return p0
.end method
```

`OverviewComponentObserver.smali:559`：

```smali
.method public static isOtherDesk()Z
    invoke-static {}, ...->getPkgName()Ljava/lang/String;
    move-result-object v0
    if-nez v0, :cond_0
    sget-boolean v0, ...->sIsDefaultHome:Z
    xor-int/lit8 v0, v0, 0x1     # 无 pkgName：!isDefaultHome
    return v0
    :cond_0
    const-string v1, "com.android.launcher"
    invoke-static {v0, v1}, Landroid/text/TextUtils;->equals(...)Z
    xor-int/lit8 v0, v0, 0x1     # pkgName != "com.android.launcher"
    return v0
.end method
```

- `OplusGridRecentsConfig.isEnable()` = 折叠屏展开 || 平板，**手机上恒为 `false`**。
- 因此「手机 + 本桌面为默认 Home」→ `true`（命令队列）；「第三方桌面为默认 Home」→ `false`（冷启动）。

---

## 4. 崩溃隐患专项：getRecentsView() 的无条件类型转换

**本节所讨论的问题为原始分析文档所未覆盖，是实施「统一开关」前必须先解决的前置缺陷。**

### 4.1 现象

`OplusBaseTouchInteractionService.smali:10449` 的 `getRecentsView()`：

```smali
.method public final getRecentsView()Lcom/android/quickstep/views/OplusRecentsViewImpl;
    iget-object v0, p0, ...->mOverviewComponentObserver:...     # 1. 取 observer
    if-eqz v0, :cond_0                                          # null → 返回 null
    ...->getActivityInterface() -> v0
    if-eqz v0, :cond_0                                          # null → 返回 null
    ...->getCreatedActivity()    -> p0
    if-eqz p0, :cond_0                                          # null → 返回 null
    invoke-virtual {p0}, Landroid/app/Activity;->isDestroyed()Z
    if-nez v0, :cond_0                                          # destroyed → 返回 null
    check-cast p0, Lcom/android/launcher3/Launcher;             # ← :10495 无条件强转
    invoke-virtual {p0}, ...->getOverviewPanel()Landroid/view/View;
    instance-of v0, p0, ...->OplusRecentsViewImpl;
    if-eqz v0, :cond_0
    move-object v1, p0
    :cond_0
    return-object v1
.end method
```

### 4.2 为什么第三方桌面场景下会崩溃

| 场景 | `getActivityInterface()` | `getCreatedActivity()` | `check-cast Launcher` |
| --- | --- | --- | --- |
| 本桌面是默认 Home | `LauncherActivityInterface` | `Launcher`（**是** `Launcher`） | 通过 |
| **第三方桌面（非默认 Home）** | `FallbackActivityInterface` | `RecentsActivity`（**不是** `Launcher`） | **抛 `ClassCastException`** |

- `FallbackActivityInterface#getCreatedActivity()`（`:210-224`）返回 `RecentsActivity`（或 `null`）：

```smali
.method public getCreatedActivity()Lcom/android/quickstep/RecentsActivity;
    sget-object p0, Lcom/android/quickstep/RecentsActivity;->ACTIVITY_TRACKER:...
    invoke-virtual {p0}, ...->getCreatedActivity()Lcom/android/launcher3/BaseActivity;
    move-result-object p0
    check-cast p0, Lcom/android/quickstep/RecentsActivity;
    return-object p0
.end method
```

- 类层次（依据 `smali` 头部的 `^\.class` / `^\.super`）证实两者为**兄弟类**：

```
RecentsActivity
  └─ OplusBaseRecentsActivity        (smali_classes2/.../OplusBaseRecentsActivity.smali:1-2)
        └─ StatefulActivity          (smali_classes3/.../statemanager/StatefulActivity.smali:1-2)
              └─ BaseDraggingActivity
                    └─ BaseActivity ── (…) ──> android.app.Activity

Launcher                             (smali_classes3/.../launcher3/Launcher.smali:1-2)
  └─ StatefulActivity ── 同上层
```

- `getOverviewPanel()` 的声明在两者中均可用：`BaseDraggingActivity.smali:403` 为
  `public abstract getOverviewPanel()Landroid/view/View;`；`Launcher.smali:7589`、
  `RecentsActivity.smali:758` 各自以 `public` 覆写。

### 4.3 触发条件与后果

- 触发条件：**只有在 `isSupportMultiTapMenu()` 被强制为 `true` 之后**才会走到 `getRecentsView()`
  （原始逻辑下该值为 `false`，代码直接落 `:cond_7`，不会调用该方法）。
- 触发时机：当存在存活的 `RecentsActivity`（最近任务已打开 / 刚使用过）时；
  若从未打开过最近任务，`getCreatedActivity()` 为 `null` → 返回 `null` → 不会崩溃。
- 后果：`ClassCastException` 沿 `lambda$8 → m2.run → lambda$12 → onOverviewToggle` 向上抛出，
  该调用链无 `try/catch`，最终击垮桌面的手势服务进程，任务键表现为「按下无反应 / 一段时间后恢复」。

### 4.4 对照：同一方法中的另一处类型转换带有保护

`onOverviewToggle` 自身在 `:12721` 处对同类对象采用的是**先判类型、再转换**的写法：

```smali
12721  instance-of v8, v7, Lcom/android/launcher/Launcher;
12723  if-eqz v8, :cond_f
12725  check-cast v7, Lcom/android/launcher/Launcher;
```

这说明「先判空、再判类型」在该类中是常规写法，**唯独 `getRecentsView()` 遗漏了该保护**，
从侧面印证它是一条「只在默认 Home 下才会被调用、从未考虑 fallback 场景」的盲区。

### 4.5 兜底取值方案（LaFix 采用）

在 `isOtherDesk() == true` 时跳过会崩溃的原实现，直接：

```
activity = getActivityInterface().getCreatedActivity()
if (activity == null || activity.isDestroyed()) return null
panel = activity.getOverviewPanel()                      # Launcher / RecentsActivity 通用
return panel instanceof OplusRecentsViewImpl ? panel : null
```

- fallback 的视图 `FallbackRecentsView → OplusStackRecentsView → OplusGridRecentsView →
  OplusRecentsViewImpl`（`FallbackRecentsView.smali:1-2`、`OplusStackRecentsView.smali:1-2`、
  `OplusGridRecentsView.smali:1`），**本身即 `OplusRecentsViewImpl`**，因此可通过类型判定。
- 取不到时返回 `null`，派发点将安全回退到冷启动，其行为与打补丁前保持一致。

---

## 5. 关键常量速查

| 名称 | 值 | 位置 |
| --- | --- | --- |
| `BUTTON_RECENTS` | `0x4` | `TaskbarNavButtonController.smali:30` |
| `TRANSACTION_onOverviewToggle` | `0x7` | `ILauncherProxy$Stub.smali:73` |
| `TYPE_TOGGLE` | `4` | `OverviewCommandHelper.smali:31` |
| `MAX_QUEUE_SIZE` | `3` | `OverviewCommandHelper.smali:19` |
| `LAUNCH_TASK_LONGEST_TIME_GAP` | `0x3e8L`（1000ms） | `RecentsViewAnimUtil.smali:482` |
| `LAUNCH_TASKVIEW_TIME_GAP` | `0x96`（150ms） | `RecentsViewAnimUtil.smali:478` |
| `LAST_START_NEW_TASK_TIME_GAP` | `0x1f4`（500ms） | `RecentsViewAnimUtil.smali:472` |
| `EXECUTE_COMMAND_INTERVAL` | `0x1f4`（500ms） | `OplusOverviewCommandHelperImpl.smali:134` |
| `APP_TO_OVERVIEW_WINDOW_ANIM_TIMEOUT` | `0x3e8`（1000ms） | `AppToOverviewAnimationProvider.smali:18`（应用于 `:2085`） |
| `RECENTS_ANIMATION_TIMEOUT` | `0x3e8`（1000ms） | `RecentsActivity.smali:19`（应用于 `:745`） |
| `APP_TO_OVERVIEW_CONTINUATION_TIME_OUT` | `0x64`（100ms） | `AnimationController.smali:257` |
| `RECENT_ANIM_FINISH_TIME_OUT` | `0x5dc`（1500ms） | `AnimationController.smali:265/269/271` |
| `LAND_SPACE_RECENT_ANIM_TIME_OUT` | `0x9c4`（2500ms） | `AnimationController.smali:261` |
| 回 Home 转场结束超时 | `0xbb8`（3000ms） | `GestureViewModel.smali`（0xbb8 处） |
| `getRecentsLaunchDuration()` | 480 / 300 / 250ms | `AppToOverviewAnimationProvider.smali:527-554` |

> 注意：上表常量多数已在**编译期内联为立即数**（例如 `checkLaunchTaskLongestTimeGap()` 中直接出现
> `const-wide/16 v2, 0x3e8`），因此运行时修改字段无效，只能通过修改方法行为来生效（即 LaFix 采用的做法）。

---

## 6. 症状、根因与修复对照

| 用户可见症状 | 根因（文件:行） | LaFix 处置 |
| --- | --- | --- |
| 点任务键慢 / 偶发白帧（每次冷启动独立 Recent Activity） | `OplusBaseTouchInteractionService.smali:8282-8364`；`FallbackActivityInterface.smali:692` | T1：强制 `isSupportMultiTapMenu()`；T1′：`getRecentsView()` 兜底（首屏仍会冷启动一次，见限制） |
| 连点几次后「点了没反应」 | `OplusRecentsViewImpl.smali:24428/24434`（1s 守卫） | T2：`checkLaunchTaskLongestTimeGap()` → true |
| 按 Home 后 0.25s 内点任务键无反应（返回键正常） | `OplusBaseTouchInteractionService.smali:8042-8062`（Home+菜单同击守卫，250ms）+ `RemoteToRecentsTransitionHelper$homeToRecentsCallback$1.smali:72/76`（置位 `isHomeKeyMenuKeyTogetherClick` / 刷新 `lastHomeKeyRecentAnimStartTime`） | T3：`isHomeKeyMenuKeyTogetherClick()` → false |
| 转场卡顿 / 卡死（等远程动画回调不到，靠写死超时兜底） | `AppToOverviewAnimationProvider.smali:2085`(1000ms)、`RecentsActivity.smali:745`(1000ms)、`AnimationController.smali:261/265`(2500/1500ms)、`GestureViewModel.smali:340`(3000ms) | 未处理（属 B 方案范畴，见下） |
| 逐任务快速切换失效 | `OplusOverviewCommandHelperImpl.smali:1094`（`quickSwitchToNextTask` 被跳过） | 随 T1 一并恢复（命令队列路径） |
| 动画完成回调丢失 | `LauncherAnimationRunner$AnimationResult.smali:178-201`（other-desk 不投递 `mOnCompleteCallback`） | 未处理 |
| 最近任务不是堆叠样式 | `OplusStackRecentsConfig.smali:258`（`isStackSupported`=false） | 未处理 |
| 「清空全部」面板位置偏 / 退出动画多一次归零 | `RecentsLayoutManager.smali:955`、`OplusClearAllPanelView.smali:3048` | 未处理 |
| Dock 切换无动画 | `DockViewController.smali:1889` | 未处理 |
| 全景 / 自适应不可用 | `OverviewComponentObserver.smali:940-967`、`PanoramaUtil.smali:299/335` | 未处理 |

> T1 / T1′ / T2 / T3 覆盖的是「任务按钮」这一主线；表中标注「未处理」的项属于范围更广的私有路径差异。
> 如需继续处理，可沿用 README 中的思路另行扩展（建议另设独立开关，避免与现有改动耦合）。

---

## 7. 复现与验证

捕获 logcat 并按 TAG 过滤，即可区分实际走的是哪条路径：

```bash
adb logcat | grep -E "OplusBaseTouchInteractionService|OplusOverviewCommandHelperImpl|OplusRecentsView|RecentsViewAnimUtil|RecentsActivityCommand|AppToOverviewAnimationProvider|LaFix"
```

关键判读如下：

| 日志 | 含义 |
| --- | --- |
| `onOverviewToggle: use command to response menu` | 走了命令队列（正常机型路径 / 修复后） |
| `onOverviewToggle isInThreeButtonMode false`，或无上条而直接 `RecentsActivityCommand--run()` | 走了冷启动 fallback（第三方桌面） |
| `executeCommand isSupportMultiTapMenu=false` | 总开关仍为 false（T1 未生效） |
| `... wait task launch refuse this time` | 被 1 秒守卫拒绝（T2 未生效） |
| `App toggle to OVERVIEW timeout in:1000` | 转场等到超时（且第三方场景下该兜底近似 no-op） |

配合 `adb logcat -s LaFix` 可确认各 Hook 是否安装成功（输出 `hooked ...` / `unsupported ...`）。

---

## 8. 与默认 Home 场景的差异对照

同一个按键，两条路径：

| 维度 | 默认 Home（本桌面） | 第三方桌面（非默认） |
| --- | --- | --- |
| 总开关 `isSupportMultiTapMenu()` | `true` | **`false`** |
| 任务键派发 | `addCommand(4)` 命令队列 | **直接 `RecentsActivityCommand.run()`（冷启动）** |
| Overview 承载 | 与 Home 同一 Activity（`isHomeAndOverviewSame=true`） | **独立 `RecentsActivity`（false）** |
| 转场动画 | 私有转场 + 命令队列协调 | **远程动画 + 写死超时兜底** |
| 逐任务快速切换 | 支持（quickSwitch） | **不支持** |
| 重复启动守卫 | 有 | **部分失效** |
| 动画完成回调 | 有 | **缺失** |
| 最近任务样式 | 可堆叠 | **非堆叠** |
| 全景 | 可支持 | **不支持** |
| `getRecentsView()` | 返回 `Launcher` 宿主视图 | **原实现会 `ClassCastException`（本仓库已兜底修复）** |

---

## 附录：易误读结论核对

以下结论在分析过程中容易被读反，已逐条核对：

- `OplusAbsOverviewProxyImpl.onOverviewHidden()` 中向 `RecentsActivity.startHome()` 投递的分支
  （`:939-947`）**仅在 `isHomeAndOverviewSame() == true` 时触发**（`:937 if-nez p0, :cond_0`），
  并非第三方桌面场景下的缺陷。
- `TouchInteractionService.preloadOverview()`（`:2048-2062`）提前 return 的触发条件为
  「三键导航 + `isHomeAndOverviewSame() == true`」，与「第三方桌面会跳过预加载」的直觉相反，
  此处仅作策略差异记录。
- `isSupportMultiTapMenu()` 的实现为 `!A && !B`（逻辑与），**并非** `||`（见第 3.1 节逐行核对）。
