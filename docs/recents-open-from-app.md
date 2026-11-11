# 「应用内打开最近任务无法触摸 / 动画闪烁」分析

- 分析对象：`com.android.launcher`（apktool 反编译工程），versionName `16.4.5` / versionCode `160040005`
- 原始工程：`/storage/emulated/0/MT2/apks/系统桌面_16.4.5_src/`，代码位于 `smali`、`smali_classes2` ~ `smali_classes7`
- 定位：本模块（LaFix）在「统一开关」之后仍然残留的一个兼容问题
- 标注：**实证** = 反编译代码中确有此分支/调用；**推断** = 由代码路径推导出的运行时后果

---

## 0. 现象与前提

前提：第三方桌面被设为系统默认 Home。

| 场景 | 结果 |
| --- | --- |
| 在**第三方应用**内，三键导航点「任务键」 | 最近任务打开后**无法进行任何触摸**；点某张任务卡只会「瞬间结束动画」并伴随内容闪烁；即使不点，动画本身也会闪烁 |
| 在**第三方 Home** 上点「任务键」 | 完全正常、动画流畅 |

要点：**同一个按键，只因为顶层任务不同（Home vs 应用），结果完全不同。**

---

## 1. 结论摘要

任务键的派发点手里有一个「顶层是不是第三方 Home」的布尔量；在「统一开关」把
`isSupportMultiTapMenu()` 拉成 true 之后，这个布尔量**直接决定了按键走哪条路**：

```
顶层是第三方 Home   → 冷启动 RecentsActivity        → 正常
顶层是第三方应用     → 命令队列（addCommand(4)）     → 混合态 → 无法触摸 / 闪烁
```

而命令队列是**为「概览由 Launcher 自身承载」设计的**：它内部的守卫、`GestureState`、
手势式输入消费者、转场回调都建立在「Home 与 Overview 同一 Activity」之上。
第三方桌面下概览由独立 `RecentsActivity` 承载（`isHomeAndOverviewSame()==false`，属 fallback
的结构必然结果），此时走命令队列即形成原厂不存在的「命令队列 + 独立概览」混合态，
于是触摸被手势式消费者接管、动画被反复重放。

**修法**：在 other-desk 下把该布尔强制为 true，让任务键与在 Home 上一致地走冷启动；
`isSupportMultiTapMenu()` 仍保持 true，因此动画完成回调、布局、Dock 等「统一下沉」的收益不受影响。

---

## 2. 任务键派发真相表（本次关键发现）

派发点：`OplusBaseTouchInteractionService.onOverviewToggle$lambda$12$lambda$8(service, p1)`
（`smali_classes2/com/android/quickstep/OplusBaseTouchInteractionService.smali:8147-8368`）

条件（**实证**）：

| 顺序 | 条件 | 结果 |
| --- | --- | --- |
| 1 | `!DisplayController.isInThreeButtonMode()` | `RecentsActivityCommand.run()`（冷启动） |
| 2 | 三键 且 `supportInterruptionNaviMode() && isOpeningAnim() && …` | `simulateUpSlideToOverview()` |
| 3 | 三键 且 `isSupportMultiTapMenu() && getRecentsView()!=null && !isRecentTasksEmpty() && !p1` | `addCommand(4)`（**命令队列**） |
| 4 | 其余 | `RecentsActivityCommand.run()`（冷启动） |

`p1` 的来源（**实证**）：`onOverviewToggle` 内以
`SpecialSceneHelper.isOtherFallbackHome(顶层任务, "com.android.launcher")` 求值
（`OplusBaseTouchInteractionService.smali:12697`；该值经 `c4`/`m2`/`g` 一路透传到派发点，
中间无改写），并交给 `m2`（`smali_classes2/com/android/quickstep/m2.smali`）。

`isOtherFallbackHome` 的语义（`smali_classes5/com/oplus/quickstep/gesture/helper/SpecialSceneHelper.smali:1001-1150`）：

> 顶层任务的 `WindowConfiguration.activityType == 2`（`ACTIVITY_TYPE_HOME`）
> **且** 顶层 activity 的包名 ≠ 传入的 `pkg`（即本桌面 `com.android.launcher`）→ 返回 true。

因此：

- 顶层是**第三方 Home** → `p1 = true` → 无论 `isSupportMultiTapMenu()` 如何，都落到「冷启动」。
- 顶层是**第三方应用** → `p1 = false` → 是否走命令队列，完全由 `isSupportMultiTapMenu()` 决定。

这就是「Home 正常 / 应用内异常」这一非对称现象的来源：**它只在 `isSupportMultiTapMenu()` 为 true 时才出现**，
也就是在本模块把 `OplusStackRecentsConfig.isOtherDesk()` 下沉为 false 之后。

---

## 3. 为什么是「统一开关」之后才出现

「统一下沉」（把 `OplusStackRecentsConfig.isOtherDesk()` 固定为 false）本身修好了一批问题：
它让 `isSupportMultiTapMenu()` 回到 true，从而使以下基于该开关的守卫/回调重新生效
（均为**实证**分支）：

| 位置 | 统一下沉前（other-desk） | 统一下沉后 |
| --- | --- | --- |
| `OplusOverviewCommandHelperImpl.smali:2613` | 不 `animSet.end(7)` 结束旧远程动画 | 正常结束 |
| `OplusOverviewCommandHelperImpl.smali:2683` | 不判「已在跑/将跑」而重复启动 | 正常去重 |
| `OplusBaseTouchInteractionService.smali:7998-8039` | 不 `endAppTransition(...)` | 正常结束旧转场 |
| `LauncherAnimationRunner$AnimationResult.smali:178-201` | 丢弃 `mOnCompleteCallback` | 正常投递 |
| `AppToOverviewAnimationProvider$1.smali:66-72` | 1s 兜底对 fallback 近似空操作 | （仍由 RecentsActivity 兜底） |

但它**同时把「任务键在应用内的派发」从冷启动改成了命令队列**，而命令队列是为
「概览由 Launcher 自身承载」设计的。该前提在 fallback 下无法成立，于是引入新的混合态：

1. **手势 / 输入管线接管触摸**：命令队列以 `createGestureState(observer, isFallbackRecents=true)`
   构造手势状态并进入手势/拖拽管线（`OplusBaseTouchInteractionService.smali:9393-9417`）；
   fallback 下 `createOtherActivityInputConsumer` 会改用 `mFallbackSwipeHandlerFactory`、
   并把「延迟启动」标志记为真（`:1475-1533`）。概览已显示后这条管线若未退出，触摸即被它消费：
   `OplusOtherActivityInputConsumer.smali:3468-3475` 在
   `ACTION_UP/CANCEL && DisplayUtils.isRemoteAnimationRunning` 时直接 `onGestureCancelled()`
   ——即「抬手即取消手势、动画被瞬间掐掉」。
2. **消费者实例不随任务键更换**：任务键分支（`:8351-8367`）**完全不写 `mConsumer`**；
   消费者只在 ACTION_DOWN（`onDispatchInputEvent:6614/6742/6762`）或 finish 回调
   （`onConsumerInactive:5324`→`reset()`）时才更换，故可长时间停在旧实例上。
3. **残留标志**：`DisplayUtils.isRemoteAnimationRunning` 只在窗口动画 `onAnimationEnd`
   （`RecentsActivityCommand$3.smali:91`）复位；转场被反复顶替时可能长期为 true，
   进而持续命中第 1 条的取消分支。
4. **转场重放**：`RecentsActivity$3.smali:41-52`（`resetTaskVisuals()`+`reapplyState()`）等，
   在命令队列反复触发转场时表现为画面跳变 / 闪烁。

> 一句话：**按键把「概览由谁承载」搞混了**——用「Launcher 内概览」的命令队列去驱动独立
> `RecentsActivity`，后续围绕该前提的守卫、回调、输入消费者自然对不上号。
>
> 以上第 1-4 点的代码分支均为**实证**；「三者叠加即表现为无法触摸 + 闪烁 + 点击瞬间结束」
> 为**推断**（受第 5 节的运行期验证手段约束）。

---

## 4. 修复设计

### 4.1 任务键派发对齐：按「概览是否已在前台」分流

`RecentsKeyFixes#alignTaskKeyDispatch`（`app/src/main/java/com/enoa/lafix/RecentsKeyFixes.java`）：hook
`OplusBaseTouchInteractionService.onOverviewToggle$lambda$12$lambda$8(OplusBaseTouchInteractionService, boolean)`，
在 other-desk 下按下表分流：

| 概览状态 | 处理 | 效果 |
| --- | --- | --- |
| 未展开 | 把派发布尔改写为 `true`（视作「顶层是第三方 Home」） | 与在 Home 上一致，走 `RecentsActivityCommand` 冷启动打开 |
| 已在前台（`isStarted() && !isPaused()`，含正在进入的动画） | 交回原有派发 | 走命令队列 `addCommand(4)` → `executeCommand`，即原厂「多击遍历任务」（自带 500ms 合并 / `deferExecuteCommand` / 动画互斥守卫） |

### 4.2 两个边界都不能单独放宽

**不能一律放开命令队列**：`isOtherDesk` 下沉为 false 后 `isSupportMultiTapMenu()` 恒为 true，
而 `getRecentsView()` 在 fallback 下只要 `RecentsActivity` 实例还在就非空——**包括概览并未显示的时候**。
此时命令队列会以「Launcher 内概览」的方式去打开概览（`executeCommand` 主路径 →
`TaskAnimationManager.startRecentsAnimation`，`OplusOverviewCommandHelperImpl.smali:3295`），
即先前修复的混合态 bug（手势式输入接管 / 动画重放）。

**不能一律冷启动**：`RecentsActivityCommand.handleCommand()` 判断概览可见用的是
`BaseActivityInterface.getVisibleRecentsView()`，而 fallback 实现要求
`RecentsActivity.hasBeenResumed()`（`FallbackActivityInterface.smali:369-408`）；
进入动画尚未结束时其为 null，于是 `handleCommand` 落进
`elapsed < doubleTapTimeout → return true` 分支（`RecentsActivityCommand.smali:668-672`），
把这一次按键**静默吞掉**——表现为「快速连按没有反应」。

因此判据取 `isStarted() && !isPaused()`：既能在动画期间就认定「概览已在前台」（连按交给命令队列，
不再被 `handleCommand` 吞掉），又能在概览真的没显示时把按键挡回冷启动（不再触发混合态）。

### 4.3 保留收益与取舍

- `isSupportMultiTapMenu()` 仍为 true，`AnimationResult` 的完成回调、Dock、布局、清除面板定位等
  「统一下沉」项不受影响。
- 命令队列只在「概览已在前台」时被用到；此时 `executeCommand` 走的是 `launchTaskOnToggle`
  / `launchTask` 的按类型分派，不会走到启动概览的主路径。
- 保留原厂观感：连按任务键仍是「多击遍历任务」，而不是本模块自定义的跳转。

---

## 5. 验证方法

复现：第三方桌面为默认 Home → 三键导航 → 在任意应用内点任务键。

```bash
adb logcat | grep -E "onOverviewToggle: use command to response menu|RecentsActivityCommand--run|launchTaskOnToggle|showNextTask|need reload task, refuse|setInputConsumer:|App toggle to OVERVIEW timeout in:1000"
```

判读：

- **打开（概览未展开）**：修复前应用内会出现 `onOverviewToggle: use command to response menu`
  （走命令队列 → 命中混合态）；修复后不再出现，改为 `RecentsActivityCommand--run()`，与 Home 场景一致。
- **连按（概览已在前台）**：修复后应重新出现 `onOverviewToggle: use command to response menu`
  （走命令队列的多击遍历），并可见 `addCommand(), type = 4`；若出现
  `executeCommand ... recents animation already running or pending` 说明连按被正确延迟执行。
- 若「无法触摸 / 闪烁」仍复现，再补抓 `setInputConsumer:`（消费者是否长期停在
  `TYPE_OTHER_ACTIVITY`）与 `DisplayUtils.isRemoteAnimationRunning` 的翻转点，以区分残留消费者 / 残留标志。
- 在第三方 Home 上点任务键：修复前后都应走冷启动（`p1=true` 本就如此），行为不应变化。

---

## 6. 风险与回退

- 该 Hook 仅在 `isOtherDesk()` 为 true（本桌面不是默认 Home）时介入；否则完全直通。
- 「是否已在前台」判定只用 `Activity.isDestroyed()/isFinishing()` 与 launcher3 的
  `BaseActivity.isStarted()/isPaused()`；任何取值异常都退回「未展开」分支，即冷启动——与修复前一致，不会更差。
- 若系统桌面升级导致方法名 `onOverviewToggle$lambda$12$lambda$8` 变化，Hook 安装失败并记录
  `task-key-dispatch unsupported, skipped`，自动回退原行为，不影响其余修复。
- 回退方式：删除 `RecentsKeyFixes.install()` 中的 `alignTaskKeyDispatch(scope);` 一行即可。

---

## 7. 从「冷启动应用」进入概览：运行任务缺失导致的自聚焦 / 自跳转

### 7.1 现象

复现：后台保留 c、b（b 更近）→ 回主屏 → 点图标打开此前没有后台任务的应用 a → 在 a 内按任务键。

| 步骤 | 期望 | 实际 |
| --- | --- | --- |
| 进入概览 | 聚焦 a 之前那个任务 b | 聚焦第 0 张卡＝a 自身 |
| 再按任务键 | 跳转到 b | 仍回到 a |

前提：概览卡带顺序为**第 0 张＝最近任务**（`applyLoadPlan()` 的 `attachAndBindChilds()` 自列表末端
向前装配），所以 a 就是第 0 张、b 是第 1 张。

### 7.2 根因：`mRunningTaskViewId` 停在 -1（实证）

概览的「运行任务」在 `RecentsView.showCurrentTask(Task[],Z)`（`:16727`）里一次性写入：

```java
mRunningTaskId = -1;
if (tasks.length == 0 || tasks[0] == null) return;      // ← 提前返回时整个字段不动
...
if (shouldAddStubTaskView(tasks)) { ... addViewCompat(..., 0); v4 = 新卡.taskViewId; }
else { TaskView t = getTaskViewByTaskId(tasks[0].key.id); v4 = t != null ? t.taskViewId : -1; }
setCurrentTask(v4);                                      // → setRunningTaskViewId(v4)
```

`tasks` 来自 `AppToOverviewAnimationProvider.onActivityReady()`（`:1884-1906`）：
`TopTaskTracker.getCachedTopTask(false).getPlaceholderTasks()`，即**当前顶层任务**。

冷启动的应用：其卡片要等最近任务列表加载完（`applyLoadPlan()`）才存在，而 `showCurrentTask()` 在
`RecentsActivity` 就绪时执行、早于该时刻。于是 `getTaskViewByTaskId(顶层任务 id)` 返回 null，
`v4 = -1`，`mRunningTaskViewId` 停在 -1。`getTaskViewFromTaskViewId(-1)`（`:10450`）直接返回 null，
`getRunningTaskView()`（`:9831`）因此恒为 null，**此后没有任何路径重新绑定**。

### 7.3 为什么表现成「自聚焦 + 自跳转」

运行任务缺失时，任务键的跳转与聚焦会同时退化为「第 0 张卡＝a」：

- 跳转：`OplusOverviewCommandHelperImpl.handleLaunchTask()`（`:820-846`）对 fallback 走
  `OplusRecentsViewImpl.showNextTask()`（`:24290`）。其 `running == null` 分支先试
  `RecentsViewAnimUtil.getRunningTaskAnimRunning()`——它在概览窗口动画 `onAnimationEnd`
  （`RecentsActivityCommand$3:81`）已被复位为 -1——于是取 `getTaskViewAt(0)`＝a。
- 聚焦：本模块 `alignOverviewFocusTarget` 在「无运行任务视图」分支把目标对齐到第 0 页，同样是 a。

两者一致地指向 a，于是「进入概览聚焦自己、再按回到自己」。

### 7.4 处置：惰性纠正 `getRunningTaskView()`

`RecentsKeyFixes#recoverRunningTaskView`：第三方桌面下 hook `RecentsView.getRunningTaskView()`，
仅在**原值已是 null**、且判定「本次概览是从某个应用（不是桌面）进入」时，返回**第 0 张卡**
（`getTaskViewAt(0)`）。列表顺序为第 0 张＝最近任务，进入概览的那个应用正是最近任务，所以这张卡
就是运行任务；此后 `getNextTaskView()`（`= getTaskViewAt(runningIndex + 1)`）、`showNextTask()`
以及本模块的聚焦目标都会一致地落到第 1 张＝b。

「是否从应用进入」取两道判据之一（都沿用模块既有口径，取或）：

| 判据 | 来源 |
| --- | --- |
| 任务键派发入参的**原始**布尔（`SpecialSceneHelper.isOtherFallbackHome(顶层任务, "com.android.launcher")`，true = 顶层是第三方桌面本身） | `alignTaskKeyDispatch` 改写为 true 之前先记入 `sOverviewOpenedFromApp` |
| 窗口动画来源包名非空且 ≠ 默认 Home 包名 | `RecentsViewAnimUtil.getMWindowAnimationPkg()` + `OverviewComponentObserver.getDefaultHome()`，与 `alignOverviewFocusTarget` 同源 |

从主屏进入时两道判据都为「否」，保持 null → 交回原有行为：聚焦与跳转都落在第 0 张（＝最近打开的
应用）。任务列表尚未加载、取不到第 0 张卡时同样保持 null，与修复前一致，不会更差。

> 上一版曾按 `TopTaskTracker.getCachedTopTask(false)` 的顶层任务去列表里定位卡片；实测无效——**冷启动
> 应用的任务尚未进入该缓存**（`getPlaceholderTasks()` 正是 `showCurrentTask()` 当初用来绑定卡片的
> 同一份数据，它取不到，本 Hook 自然也取不到）。故改按上表两道与具体缓存无关的判据。

### 7.5 验证与回退

```bash
adb logcat -s LaFix | grep -E "hooked: running-task-recover|running-task-recover:"
```

`running-task-recover: page 0 used as running task (openedFromApp=…, source=…)` 是本 Hook 生效时的
单行诊断（同一「标记 + 来源包名」组合只记一次）；若复现时**没有**这一行，说明两道判据都没认定为
「从应用进入」，问题在判据而不在纠正逻辑。

真机判定（第三方桌面为默认 Home，三键导航）：

| 操作 | 修复前 | 修复后 |
| --- | --- | --- |
| 冷启动 a → 应用内按任务键 | 聚焦第 0 张（a） | 聚焦第 1 张（b） |
| 再按任务键 | 回到 a | 跳转到 b |
| 主屏按任务键（从桌面进入） | 聚焦 / 跳转第 0 张 | 不变 |
| 概览里按返回 | 回到打开概览前的应用 | 不变（不受本 Hook 影响） |

回退：删除 `RecentsKeyFixes.install()` 中的 `recoverRunningTaskView(scope);` 一行即可。
