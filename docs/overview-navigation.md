# 概览里的 Home 键与返回键

第三方桌面为默认 Home 时，概览（最近任务列表）由独立 `RecentsActivity` 承载。在这个前提下，
`Home` 键与导航栏 `返回` 键各有一处不一致：Home 键丢失「列表滑出」动画，返回键回到桌面而不是
回到打开列表前的应用。本文记录两处链路的反编译核对与处置。

- 分析对象：`com.android.launcher` 16.4.5（versionCode `160040005`），原始工程
  `/storage/emulated/0/MT2/apks/系统桌面_16.4.5_src/`
- 标注：**实证** = 反编译代码中确有此分支；**推断** = 由代码路径推导
- 实现位置：`app/src/main/java/com/enoa/lafix/`（`HomeFixes`、`RecentsKeyFixes`、`RecentsOverview`）

---

## 1. Home 键：概览在前台时必须放行原实现

### 1.1 链路（实证）

```
SystemUI 拦截 Home 键
  └─ RemoteToRecentsTransitionHelper$homeToRecentsCallback$1.notifyInterceptHomeKeyEvent(KeyEvent)
       ├─ 若干守卫（PreStartAnimRunning / overviewToggleRecents / 同击阈值 / disallowSimulateUp）
       └─ 投递 Runnable → notifyInterceptHomeKeyEvent$lambda$1(service)
            └─ OplusBaseTouchInteractionService.simulateUpSlide$default(service, false, 1, null)
```

`simulateUpSlide$default`（`smali_classes2/.../OplusBaseTouchInteractionService.smali:8525-8540`）：

```smali
const/4 p3, 0x1
and-int/2addr p2, p3
if-eqz p2, :cond_0
move p1, p3          # 掩码位 0x1 被使用时，实参取默认值 = true
:cond_0
invoke-virtual {p0, p1}, ...->simulateUpSlide(Z)V
```

即 **Home 键实际调用 `simulateUpSlide(true)`**。而 `LauncherAnimationRunner.smali:722` 调用的是
`simulateUpSlide(false)`（常量 `0x0`），是另一条路径。

### 1.2 原实现做什么（实证）

`simulateUpSlide(Z)`（`:13089-13560`）的末尾（`:13520` 起）才是真正干活的地方：

```smali
invoke-direct {p0, v0}, ...->forceNewConsumer(...)         # 新建 OplusOtherActivityInputConsumer
invoke-direct {p0, v2, v1}, ...->swichConsumer(...)
invoke-virtual {v1, v4}, ...->startWindowAnimation(Z)V     # 启动远程窗口动画
invoke-virtual {v1, p1}, ...->forceToHome(Z)V              # ← p1 即入参
```

`OplusOtherActivityInputConsumer.forceToHome(Z)`（`:1579` 起）会
`AbsSwipeUpHandler.setIsSimulateSlideFromHome(Z)` 并投递一次模拟滑动
（`onGestureEnded(负速度, ...)`），由此驱动平台自己的「滑回桌面」转场；概览侧另有
`RecentsActivity.startHome()`（`RecentsActivity.smali:1310` → `startHomeInternal()` `:387`）用
`mAnimationToHomeFactory` + `RemoteTransition("StartHomeFromRecents")` 负责把 Home 窗口拉起来。
概览在前台时，这就是用户期望的「任务列表滑出、露出桌面」。

> 注意：原实现内部**没有**任何「概览是否在前台」的分支，两种场景都走到这段代码；
> 其 `:cond_15`（`"ignore simulateUpSlide due to launcher has resumed"`）在 fallback 下是死代码——
> 它要求 `isLauncherVisible()==true`，而该方法把 created activity 强转 `Launcher`，
> fallback 下恒为 false。

### 1.3 缺陷与处置

模块原先把满足条件的 `simulateUpSlide(true)` 一律改为直接 `startActivity(homeIntent)` 并返回。
该启动**不带任何 `ActivityOptions` / `RemoteTransition`**，等于把上面那层转场整段丢弃，
窗口被直接切换，表现为**瞬间切到桌面（闪烁）**。

处置（`HomeFixes#dispatchHomeIntentWithoutSimulatedExit`）：在原有条件上追加
「概览未在前台」判据（`RecentsOverview#showing() == null`，即 `RecentsActivity` 未处于
`isStarted() && !isPaused()`）。概览在前台——含正在进入的动画——时一律放行原实现：

- 概览已展开：原实现播放「滑回桌面」转场；
- 任务键打开概览的动画途中按 Home：原实现打断当前动画并重播该转场。

概览不在前台（普通应用内按 Home）时仍按原策略改派 Home 意图，用于规避 0.9s 输入接管。

> 说明：概览不在前台时，直派 Home 意图同样会跳过原实现的转场（应用 → 桌面）。
> 该取舍沿用模块既有策略，本次未改动；如需恢复该动画，只需移除这一处的改派。
> 若某次判定仍把「概览在前台」漏判为「不在前台」，可考虑改用 service 的
> `isLauncherResumed()`（`OplusBaseTouchInteractionService.smali:11003`，fallback 下即
> `RecentsActivity.isResumed()`）；当前实现取 `isStarted() && !isPaused()`，是为了把
> 「已 started 但尚未 resumed 的进入首帧」也算作前台。

---

## 2. 返回键：恢复父类「启动运行任务」的语义

### 2.1 链路（实证）

```
导航栏返回键
  └─ Activity.onBackPressed()（application 级 android:enableOnBackInvokedCallback="false"，
     RecentsActivity 未覆盖 → 不走预测式返回）
       └─ RecentsActivity.onBackPressed()V        (RecentsActivity.smali:805-811)
            └─ RecentsActivity.startHome()V        (:1310) → startHomeInternal() (:387)
                 └─ LauncherAnimationRunner(mAnimationToHomeFactory) + RemoteTransition("StartHomeFromRecents")
                      → OverviewComponentObserver.startHomeIntentSafely(...)   # 回桌面
```

对照父类 `OplusBaseRecentsActivity.onBackPressed()V`（`OplusBaseRecentsActivity.smali:308-330`）：

```smali
invoke-virtual {p0}, ...->getMFallbackRecentsView()...
move-result-object v0
invoke-virtual {v0}, ...->getRunningTaskView()...
move-result-object v0
if-nez v0, :cond_0
invoke-super {p0}, Landroid/app/Activity;->onBackPressed()V     # 无运行任务 → 结束概览活动
goto :goto_0
:cond_0
invoke-virtual {v0}, ...->launchTaskAnimated()...              # 有运行任务 → 启动它
```

即**父类已经实现了「返回＝回到运行任务」**，而 `RecentsActivity` 用 `startHome()` 覆写了它。
stock 场景下概览由 Launcher 自身承载，返回走 `OverviewState.onBackPressed()`，它同样先取
`getRunningTaskView()` 并启动该任务（`OverviewState.smali:867-915`，`:386`/`:393` 调
`launchTasks()`，liveTile 不可用时回退 `launchTaskAnimated()`）；只有运行任务卡为空等情况
才回退到状态机（`super.onBackPressed()` → `goToState(NORMAL)`，即回桌面）。
所以只有 fallback 会出现「返回一律回桌面」的差异。

### 2.2 运行任务是「打开概览前的那个应用」（实证）

- `RecentsView.getRunningTaskView()`（`smali_classes3/.../RecentsView.smali:9831`）=
  `getTaskViewFromTaskViewId(mRunningTaskViewId)`；
- `mRunningTaskViewId` 仅由 `setRunningTaskViewId(int)`（`:3786`）与初始化（`:956`）写入，
  由 `resetRunningTaskViewId()`（`:14355`）复位为 -1；
- 其调用点位于 `setCurrentTask(int)`（`:15260`）等「任务列表更新」路径，**不随翻页变化**
  （翻页走 `setCurrentPage`）。

因此「从 c 打开概览、滚到 b，按返回」→ 运行任务仍是 c → 回到 c。

### 2.3 处置

`RecentsKeyFixes#fixBackKeyFromOverview`：hook `RecentsActivity.onBackPressed()`，仅 other-desk 生效：

1. 取概览承载视图（`BaseDraggingActivity.getOverviewPanel()`，fallback 下即 `FallbackRecentsView`）；
2. 取运行任务卡片（`RecentsView.getRunningTaskView()`）；
3. 命中则 `TaskView.launchTaskAnimated()` 并消费这次返回；未命中则放行原实现，保持原有回桌面行为。

判定为「未命中」的两种情形：

- 运行任务卡为空（例如从未有前台应用）；
- 运行任务卡**就是桌面自身那张卡**——fallback 会把桌面也作为一张卡放进列表
  （`FallbackRecentsView.smali:28/110-178/220-240`），从主屏打开概览时运行任务即它；
  此时应从原实现回桌面，而不是从卡片启动桌面任务（`RecentsOverview#homeTaskView()`）。

### 2.4 已知取舍

- 本实现复刻的是**父类的语义**（有运行任务就启动，否则回桌面），与 stock 的
  `OverviewState.onBackPressed` 语义一致；但 stock 额外的三类守卫本实现未加：
  「任务正在移除 / 全清动画中 / 网格拖拽中」时把返回延后到动画结束
  （`OverviewState.smali:897-915`）、三键导航 + 入场动画进行中改为「翻下一页」（`:281-344`）、
  快速搜索框任务特判。这些属于增强项，不是崩溃风险（`launchTaskAnimated()` 自身对
  `mTask == null` 与启动失败都有兜底）。
- 反射调用**不能**用来「省事地调父类实现」：`Method.invoke` 在 ART 上仍是虚派发，会对
  `OplusBaseRecentsActivity.onBackPressed` 再次落进子类覆写，等于没有效果；因此这里复刻了两行逻辑。

---

## 3. 验证

```bash
./gradlew :app:assembleRelease
adb logcat -s LaFix | grep -E "hooked: (home-key-dispatch|overview-back-key)"
```

真机判定：

| 操作 | 期望 |
| --- | --- |
| 概览展开时按 Home | 任务列表滑出、露出桌面（不再闪烁） |
| 任务键打开概览的动画途中按 Home | 打断当前动画并改为滑出 |
| 概览里（从应用 c 打开、滚到 b）按返回 | 回到 c（不是桌面，也不是 b） |
| 从桌面打开概览后按返回 | 回桌面（或无运行任务时的原行为） |

回退：移除 `HomeFixes` 中的 `overview.showing() == null` 判据、或删除
`RecentsKeyFixes.install()` 中的 `fixBackKeyFromOverview(scope);` 一行即可。
