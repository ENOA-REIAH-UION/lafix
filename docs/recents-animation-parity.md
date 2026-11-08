# 第三方桌面下「按任务键进入最近任务」动画对齐：分析与修复方法

- 分析对象：`com.android.launcher`（apktool 反编译工程），versionName `16.4.5` / versionCode `160040005`
- 原始工程：`/storage/emulated/0/MT2/apks/系统桌面_16.4.5_src/`（`smali`、`smali_classes2` ~ `smali_classes7`）
- 目标：第三方桌面为默认 Home 时，在主屏按任务键进入最近任务的动画，应与系统桌面为默认 Home 时**一致**（桌面淡化缩放 + 任务卡片依次滑入并平铺）
- 标注：**实证** = 反编译代码中确有此分支/常量；**推断** = 由代码路径推导
- 相关：任务按钮事件链见 [`reverse-engineering.md`](reverse-engineering.md) 第 2 节；列表/动画总览见 [`recents-third-party-vs-stock.md`](recents-third-party-vs-stock.md)

> **实现状态**：本文是逐轮排查的过程记录，正文保留了大量已被推翻或合并的中间方案。
> 下列为**当前实现**（源码 `app/src/main/java/com/enoa/lafix/`），文中与之冲突处以代码为准：
> §4.2、§9–§18、§22.3 所述的 Hook 大多已删除或改写。
>
> | 目标 | 位置 |
> | --- | --- |
> | home / 壁纸起始 alpha 对齐（缺口 ③） | `RecentsAnimationFixes.install`（`isDefaultHome`） |
> | 聚焦目标 = 任务键真实跳转目标 | `RecentsKeyFixes#alignOverviewFocusTarget` |
> | 逐卡样式输入 = 卡片真实位置（方案 G） | `RecentsKeyFixes#fixCardInsertionInterpolation` |
>
> 缺口 ② （任务卡片依次滑入）与缺口 ①（桌面缩放 / 景深）**均不再由模块补**：前者靠原厂布局完成，
> 后者在 fallback 下无宿主（见 §5）。

---

## 0. 结论摘要

同一个任务键、同样的目标（最近任务），两条路径的**动画驱动方式完全不同**：

| | 不用第三方桌面（stock） | 用第三方桌面（fallback） |
| --- | --- | --- |
| 承载 | Launcher 自身 Activity | 独立 `com.android.quickstep.RecentsActivity` |
| 进入方式 | `StateManager.goToState(OVERVIEW)`（**状态切换**） | `RecentsActivityCommand` → `AppToOverviewAnimationProvider.createWindowAnimation()`（**远程窗口动画**） |
| 桌面淡化缩放 | `DragLayerStateTranslationAnimation`（dragLayer `scale→0.92`、`alpha→0`）+ `OplusDepthController`（壁纸 zoom-out/blur） | 只有 home 图层的 `alpha = 1-progress`（`lambda$createWindowAnimation$5`），**无缩放/景深** |
| 卡片入场 | `ADJACENT_PAGE_HORIZONTAL_OFFSET 1.0→0.0` + `StackPagedViewEx` 逐卡快照 | `OplusRecentsViewImpl.getEnterAnimator()`（**被门控跳过**） |
| 时长 | 400/380ms（stack 风格 650ms + 弹簧） | `getRecentsLaunchDuration()` 480/300/250ms |

**fallback 相比 stock 有三处硬差异**，其中只有缺口 ③ 由模块修正：

| # | 缺口 | 位置 | 现行处置 |
| --- | --- | --- | --- |
| ① | 桌面/壁纸整体缩放与景深 | `AppToOverviewAnimationProvider.smali:1279-1313` | 不修（无宿主，见 §5） |
| ② | 任务卡片依次滑入 | `RecentsActivityCommand.smali:190-248` | 不补（模块不再生成逐卡入场动画，卡带由原厂布局就位，见 §4.2、§21） |
| ③ | home/壁纸层多一段 0→1 淡入 | `AppToOverviewAnimationProvider.smali:1548-1612` | 已修（§4.1，`RecentsAnimationFixes`） |

---

## 1. 两条路径的动画来源

**实证**。

- stock（`OplusOverviewCommandHelperImpl.smali:2985`，`instance-of LauncherActivityInterface`）
  → `OplusBaseActivityInterface.oplusSwitchToRecentsIfVisible()`（`:1857-2266`）
  → `StateManager.goToState(LauncherState.OVERVIEW, animated, delay, animCallback)`（`:2264`）。
  `animCallback`（`...$animCallback$1.smali`）在动画开始置 `RecentsViewAnimUtil.setDeskGoRecentsAnim(true)`、`TaskStackLayoutAlgorithm.setIsTransToStackLayout(true)`。
- fallback（`RecentsActivityCommand.smali`）
  → `registerAndStartActivity(mFallbackIntent, RecentsActivityCommand$1, …, getRecentsLaunchDuration())`
  → `RecentsActivityCommand$1.createWindowAnimation()` → `createWindowAndAdjacentAnimation()`（`:140-248`）
  → `AppToOverviewAnimationProvider.createWindowAnimation()`（`:1030-1745`）。

---

## 2. stock 的动画要素（证据）

| 要素 | 实现 | 位置 |
| --- | --- | --- |
| dragLayer 缩放 1→**0.92** / 透明度 1→**0.0** | `DragLayerStateTranslationAnimation.setStateWithAnimation` | `smali_classes3/com/android/launcher3/DragLayerStateTranslationAnimation.smali:391-528`；目标值 `OverviewState.getDragLayerScaleAndAlpha`（`:548-580`） |
| 壁纸 zoom-out / blur | `OplusDepthController.setStateWithAnimation` → `DepthAnimImpl.stateSwitchForZoomOut/WallpaperBlur` | `smali_classes3/com/android/launcher3/uioverrides/states/OplusDepthController.smali:4327-4703` |
| 概览缩放 / 偏移 | `RECENTS` 缩放 + `ADJACENT_PAGE_HORIZONTAL_OFFSET 1.0→0.0`（stack 风格按几何算目标） | `smali_classes3/com/android/quickstep/uioverrides/OplusRecentsViewStateControllerImpl.smali:4040-4115, 508-1293` |
| 逐卡位移（“依次滑入并平铺”） | `ADJACENT_PAGE_HORIZONTAL_OFFSET` setter → `updatePageOffsets()` 逐卡设 `TRANSLATION_X`；`StackPagedViewEx.computeRealTransX` 以每卡快照（`mFromMin/mScaleFromMin`）插值 | `RecentsView$5.smali`；`RecentsView.smali:19102-19368`；`smali_classes5/com/oplus/quickstep/views/StackPagedViewEx.smali:4413-4530` |
| scrim | `OplusOverviewScrim` progress | `OplusRecentsViewStateControllerImpl.smali:4300-4310` |
| 时长/插值器 | `OverviewState.getTransitionDuration` 400/380ms；stack 风格 650ms + `COUISpringInterpolator` | `OverviewState.smali:655-672`；`OplusQuickstepAtomicAnimationFactoryImpl.smali:457-935` |

---

## 3. fallback 的动画与三处缺口

### ① 桌面缩放/景深缺失（**实证**）

```smali
# AppToOverviewAnimationProvider.smali:1279-1313
instance-of v0, v15, Lcom/android/launcher3/uioverrides/states/OplusDepthController;
if-eqz v0, :cond_5                      # v15 = mActivityInterface.getDepthController()
    ...
    invoke-virtual {v7, BACKGROUND_APP, OVERVIEW, v13, TOUCH_RESPONSE_INTERPOLATOR}, OplusDepthController;->addDepthAnimation(...)
:cond_5
```

取值链：`LauncherActivityInterface.getDepthController()` 返回 Launcher 的 `OplusDepthController`（`LauncherActivityInterface.smali:470-490`）；`BaseActivityInterface.getDepthController()` **默认 `return null`**（`BaseActivityInterface.smali:1445-1452`）；`FallbackActivityInterface` **未重写** ⇒ fallback 恒 `null` ⇒ 整段跳过。

### ② 卡片滑入被门控跳过（**实证**）

```smali
# RecentsActivityCommand.smali:190-248
instance-of v0, p3, Lcom/android/quickstep/views/OplusRecentsViewImpl;
if-eqz v0, :cond_3
    invoke-virtual {v0}, OverviewComponentObserver;->isHomeAndOverviewSame()Z
    if-eqz v0, :cond_3                  # ← fallback：isHomeAndOverviewSame()==false，直接跳过
    ... isTablet() / OplusGridRecentsConfig.isEnable() ...
    invoke-virtual {p0, p1}, RecentsActivityCommand;->findClosingAppTaskId(...)I
    ... isSupportAutoFocusToNextPageInOverviewState(...) ...
    if-nez p1, :cond_3
    invoke-virtual {p3, p0}, OplusRecentsViewImpl;->getEnterAnimator(I)Landroid/animation/Animator;
    invoke-virtual {p2, p0}, Landroid/animation/AnimatorSet;->play(...)
:cond_3
```

`getEnterAnimator`（`OplusRecentsViewImpl.smali:9039-9310`）：把 `getTaskViewAt(mCurrentPage+1)` 的 `COMPAT_DISMISS_TRANS_X/Y` 从 `color_task_appear_animation_over_displacement`（屏外位移）动画到 0，时长 `coui_animation_time_move_slow`，插值器 `coui_curve_ease`。

### ③ home/壁纸起始 alpha 差异（**实证**）

```smali
# AppToOverviewAnimationProvider.smali:1548-1612
if-eqz v6, :cond_e          # v6 = OverviewComponentObserver.isDefaultHome()
    const/high16 v4, 0x3f800000   # 1.0f（stock：home 常驻）
    goto :goto_5
:cond_e
    move v4, v9                   # 0.0f（fallback：home 从透明淡入）
```

---

## 4. 修复方法（缺口 ③ 现行实现见 `RecentsAnimationFixes.java`）

最早的一版新增 `installRecentsAnimationHooks()`（在 `onPackageLoaded` 中调用），仅对 other-desk 生效。
其中 §4.1 的 alpha 对齐沿用至今；§4.2 的「补挂逐卡入场动画」已废弃。

### 4.1 对齐 home/壁纸起始 alpha（缺口 ③）

```java
scope.forceBooleanWhenThirdPartyHome(Targets.OVERVIEW_OBSERVER, "isDefaultHome", true,
        "OverviewComponentObserver#isDefaultHome");
```

`isDefaultHome()` 在本包内**唯一消费者**是 `AppToOverviewAnimationProvider` 的这段动画（`grep` 全仓仅 `:1564`）；`getOverviewIntentIgnoreSysUiState()` 读的是**字段** `mIsDefaultHome`，不经此方法，故对齐它不会改变概览目标 Intent。

### 4.2 恢复卡片滑入并平铺（缺口 ②）——已废弃

> 此方案**已从代码中移除**：`getEnterAnimator` 会直接写 `translationX`，与布局 `updatePageOffsets()`
> 算出的槽位互相覆盖（同一毫秒内在 `0` 与 `-1264` 之间反复写，差值恰好一页），是「卡片 X 偏移 →
> 聚焦样式落到错卡」的直接来源；卡带的滑入由布局本身完成。相关排查见 §13–§14、§21。

原厂该段被 `isHomeAndOverviewSame()`（fallback 恒 false）挡掉。做法：hook `RecentsActivityCommand.createWindowAndAdjacentAnimation(...)`，在返回的 `AnimatorSet` 上补挂一次 `getEnterAnimator(-1)`。

```java
Method create = method(cmd, "createWindowAndAdjacentAnimation", targetArray, targetArray, targetArray);
hook(create).intercept(chain -> {
    Object result = chain.proceed();
    if (!(result instanceof AnimatorSet)) return result;
    if (!isOtherDesk(isOtherDesk)) return result;
    Object iface = ifaceField.get(chain.getThisObject());          // mActivityInterface
    Object activity = getCreatedActivity.invoke(iface);            // RecentsActivity
    Object panel = getOverviewPanel.invoke(activity);              // FallbackRecentsView
    if (!plusView.isInstance(panel)) return result;                // OplusRecentsViewImpl
    Object enter = getEnterAnimator.invoke(panel, -1);             // -1：从主屏进入无 closing app
    if (enter instanceof Animator) ((AnimatorSet) result).play((Animator) enter);
    return result;
});
```

要点：
- 使用**原厂自己的** `getEnterAnimator`，不改其时长/插值器/位移量；
- `-1` 等价于原实现的“找不到 closing app 即不排除任何卡”；
- 取不到视图或返回 `null`（如仅一张卡）时静默跳过，保持原动画。

> **注**：原厂这段还与 `!isSupportAutoFocusToNextPageInOverviewState(...)` 互斥（该开关为真时改用“自动聚焦到下一页”滚动，即先前修复的 a→b 滚动）。本节做法是**直接补挂入场动画**，不改该开关，故与聚焦对齐（现 `alignOverviewFocusTarget`）互补（后者已使聚焦索引与跳转同源）。

---

## 5. 结构性限制：缺口 ① 无宿主

`OplusDepthController`（桌面/壁纸整体缩放与景深）是 **Launcher 活动**的组件：

- `LauncherActivityInterface.getDepthController()` = `getCreatedActivity().getDepthController()`，只在 Launcher 活动存在时非空；
- `RecentsActivity` / `BaseDraggingActivity` / `StatefulActivity` 均**没有** `getDepthController`（已全仓核对）。

因此 fallback 下不存在该动画的宿主：桌面（第三方 Home 的窗口）只能被远程动画按 home 图层处理，当前仅 `setAlpha(1-progress)`，**无法复现 stock 的 0.92 缩放 + 壁纸景深**（除非在远程动画里自行为 home `SurfaceProperties` 合成 `Matrix` 缩放，属改写原厂动画，风险与收益需单独评估）。

---

## 6. 验证步骤（需真机）

1. 构建安装：`./gradlew :app:assembleRelease`，装 `app/build/outputs/apk/release/app-release.apk`。
2. LSPosed 中启用 LaFix，作用域勾选 `com.android.launcher`，重启系统桌面。
3. 将第三方桌面设为默认 Home；打开 c、b、a 三个应用后按 Home 回到主屏（`a` 为最近）。
4. 在主屏按任务键，观察：
   - 卡片是否平铺就位，且第 2 张卡不再带常亮的「聚焦」样式（缺口 ② 由原厂布局完成，模块不补入场动画）；
   - home/壁纸层**不再多一段淡入**（缺口 ③）；
   - 聚焦是否落在第一张（`a`）、再按任务键是否跳转到 `a`。
5. 期望残留：桌面**缩放/景深**仍与 stock 不同（§5）。
6. 回退：移除 `RecentsAnimationFixes.install(scope)` 调用即可恢复原动画。

---

## 7. 证据索引

| 内容 | 路径:行 |
| --- | --- |
| stock 入口 | `smali_classes2/com/android/quickstep/OplusOverviewCommandHelperImpl.smali:2985`；`OplusBaseActivityInterface.smali:1857-2266` |
| stock StateHandler 列表 | `smali_classes2/com/android/launcher/statemanager/OplusUiFactoryInjector.smali:310-560` |
| dragLayer 缩放/淡化 | `smali_classes3/com/android/launcher3/DragLayerStateTranslationAnimation.smali:391-528`；`OverviewState.smali:548-580` |
| 深度/模糊 | `smali_classes3/com/android/launcher3/uioverrides/states/OplusDepthController.smali:4327-4703` |
| 概览态属性动画 | `smali_classes3/com/android/quickstep/uioverrides/OplusRecentsViewStateControllerImpl.smali:4040-4115, 4300-4310, 508-1293` |
| 逐卡位移 | `smali_classes5/com/oplus/quickstep/views/StackPagedViewEx.smali:4413-4530`；`RecentsView.smali:19102-19368`；`RecentsView$5.smali` |
| fallback 缺口① | `AppToOverviewAnimationProvider.smali:1279-1313`；`BaseActivityInterface.smali:1445-1452`；`LauncherActivityInterface.smali:470-490` |
| fallback 缺口② | `RecentsActivityCommand.smali:140-248`；`OplusRecentsViewImpl.smali:9039-9310` |
| fallback 缺口③ | `AppToOverviewAnimationProvider.smali:1548-1612` |
| 时长 | `OverviewState.smali:655-672`；`OplusQuickstepAtomicAnimationFactoryImpl.smali:457-935`；`AppToOverviewAnimationProvider.smali:640-660` |

---

## 8. 第二轮日志（23:13）实测结论

日志 `/storage/emulated/0/09_11-23-13-03_981.log`（90 995 行 / 10.7 MB，`LaFix` 记录 15 318 条）。
**注意**：`logcat` main 环形缓冲只有 256 KiB，逐帧打印 19 张卡会被刷爆——该文件开头
（1794237171.98）不是进入动画的开头，只是缓冲圈起点，动画起始段已丢失。第三轮因此先做了去刷屏。

### 8.1 调用链（比 §4 更精确）

菜单按钮 alpha 的唯一驱动路径：

```
OplusRecentsViewImpl.updateCurveProperties()
  → OplusStackTaskView.onPageScroll → OplusTaskViewImpl.onPageScroll
  → OplusTaskViewImpl.updateCurveInterpolation(float) → View.setAlpha(menu/info)
```

`updatePageOffsets()` 只改 translation；日志中它的 155 次调用全在 `FallbackRecentsView#7f0a04f5`
上，再次确认走的是 fallback 分支。

### 8.2 卡片身份与顺序

栈式布局 `X = -794·index`。取 174.0–176.0 s 这段静息帧（偏移恰为 0，且 `X=0` 那张
scale=1.0 / menu-alpha=1.0，即聚焦卡）反推：

| index | 0 | 1 | 2 | 3 | 4 | 5 |
| --- | --- | --- | --- | --- | --- | --- |
| taskId | 13024 | 12761 | 12891 | 12622 | 13028 | 12957 |

### 8.3 异常帧（171.98–172.4 与 176.0–179.3）实测

| taskId | index | X | scaleX | menu alpha |
| --- | --- | --- | --- | --- |
| 12761 | 0 | -470 | 0.986 | 0.5 |
| 12891 | 1 | -1264 | 0.97 | 0.0 |
| 13024 | 2 | -2058 | 0.97 | 0.0 |
| 12622 | 3 | -2852 | 0.97 | 0.0 |

整条带子相对 `X = -794·index` 偏移 **-470 px**（≈0.59 页），`updateCurveProperties` 的聚焦位置
因此落不到任何一张卡上：index 0 只拿到半聚焦（0.986 / menu 0.5），相邻卡被当成非聚焦
（0.97 / 0.0）。这就是「b 呈现聚焦突出样式」的直接原因——**不是样式没附加**。同一偏移在
日志尾段（176.0–179.3）复现，说明它在动画结束后保持。

### 8.4 `a 滑入、b 没滑入` 的结构性原因

`OplusRecentsViewImpl.getEnterAnimator(I)`（`smali_classes3/.../OplusRecentsViewImpl.smali:9039-9310`）：

- 目标卡写死为 `getTaskViewAt(mCurrentPage + 1)`——**只动一张卡**；
- 起点由该卡 `getLocationOnScreen()[0]` 推算出屏外位移，动画到 `0`，property 是
  `OplusTaskViewImpl$Companion.getCOMPAT_DISMISS_TRANS_X()`，时长
  `coui_animation_time_move_slow` = 420 ms（`res/values/integers.xml:69`），插值器 `coui_curve_ease`；
- 监听器对该卡 `setEnterAnimationRunning(true/false)`。

从主屏进入时 `mCurrentPage` 为初值，`mCurrentPage+1` 落到 index 0（`a`），因此
`installRecentsEnterAnimHook` 补挂的这一次 `getEnterAnimator(-1)` 只会让 `a` 滑入，`b`/`c`
「直接就位」。实测 `ENTER-RUNNING running=false` 仅 2 次，目标分别是 `12761`、`13024`（各自会话的
index 0），与上述一致。

### 8.5 尚未取到的证据（第三轮补）
1. `CURVE`（`updateCurveInterpolation` 入参）、`SCALE-DIM`（`updateScaleDimAlpha`）一条都没有：
   两者都是极短方法（`.locals 2` / `.locals 4`），AOT 内联后 LSPosed 挂不上。
2. 偏移 470 px 的出处未知——`color_task_appear_animation_over_displacement` 只有 10 dip
   （`res/values/dimens.xml:876`），不是它。
3. 第三轮 tracer 改造：只记 `idx<=2` 的卡、只记变化、高频几何不带栈；新增 `PAGE`
   （`updateCurveProperties` 之后的 `mCurrentPage` / `mScrollState.getScroll()` / `mMaxScroll` /
   `scrollX`）与 `describe()` 的 `idx=` / `pkg=`。

### 8.6 470px 的来源：**步进 1264 vs 间距 794**

把 `TASK-TRANS-X` 按 taskId 求前后差值（只统计 |Δ|≥1），得到：

| Δ | 次数 | 时刻 |
| --- | --- | --- |
| -1264 | 98 | 171.988 – 179.248（全程） |
| +1264 | 76 | 174.132 – 177.654 |
| -794 / +794 | 19 / 19 | 174.122–174.147 / 177.615–177.621 |
| ±1..4 | 数十 | 平滑动画帧 |

即：**整条卡带是「一页一页整体平移」的，页步长 = 1264px**；而卡与卡的间距恒为 **794px**。
两者之差 `1264 − 794 = 470` 恰好就是 8.3 里那条系统错位。也就是说：每翻一页，卡带相对页原点
就偏掉 470px，曲线（`updateCurveProperties`）算出的聚焦位置便永远落不到任何一张卡的槽位上。

与 8.3 的实测吻合：聚焦槽位（X=0）右侧最近的卡只有 0.986（拿不到 1.0），其余卡全是 0.97 ——
`a` 永远到不了满聚焦态，而槽位附近的卡则表现出「半聚焦」。**这是页宽/间距不一致，不是样式没附加。**

剩下两个待定项（第三轮日志一次取全）：
1. 页步长 1264 是 `mMaxScroll/getPageWidth` 算出来的哪一路（新 `PAGE` 记录直接给出
   `mMaxScroll`、`scroll`、`scrollX`、`childCount`）；
2. 卡带里前三张卡在两次采样间发生过**顺序轮换**（`[13024,12761,12891]` ↔ `[12761,12891,13024]`），
   需要 `idx=` 才能判断是列表重排还是纯位移。

---

## 9. 修复：动画期间「非聚焦卡呈现聚焦样式」

### 9.1 直接证据（第二轮日志）

新卡的逐卡样式来得太晚：

| 时刻 | 事件 |
| --- | --- |
| 1794237171.990 | 卡 `12891` 菜单按钮 alpha = **1.0**（创建时的默认＝聚焦外观） |
| 1794237172.096 | 同一张卡 alpha = **0.0**（约 100ms 后才被曲线改成非聚焦） |

进入动画期间卡带位置在扫动，`updateCurveProperties()` 会按当前几何给每张卡反复算曲线值；
叠加「新卡默认就是聚焦外观」，于是「本该非聚焦的卡」先亮了一下聚焦样式——即用户看到的突兀聚焦。

### 9.2 改动（仅 other-desk）

`MainHook.installNewTaskStyleHook`（已移除）：

1. 新 TaskView 加入后，改为在**布局完成、首帧绘制之前**（`ViewTreeObserver.OnPreDrawListener`）
   用原厂自身的 `updatePageOffsets()` + `updateCurveProperties()` 各重算一次，使新卡一出生就是
   正确样式（原来用 `post()`，早于该卡的 layout，几何尚未就绪）。
2. **不再吞掉** `OplusTaskViewImpl.setEnterAnimationRunning(true)`：原厂正是用它让「正在做入场
   动画的那张卡」退出逐卡样式计算（`OplusRecentsViewImpl:26810/27924` 的 `goto_10`）；入场中的卡
   保持「将要聚焦」的外观是有意的，吞掉反而让它在飞行途中被曲线反复描边。

`MainHook.installRecentsEnterAnimHook`（已移除）：

3. 在窗口 AnimatorSet 的 `onAnimationEnd` / `onAnimationCancel` 上收尾一次
   `updatePageOffsets()` + `updateCurveProperties()`（post 一帧），把飞行途中可能留下的瞬时样式
   收敛到当前页。

### 9.3 验收标准（第三轮日志）

- 每张卡**首次**出现的 `MENU-ALPHA` 不应是 `1.0`，除非它当时就是当前页。
- 进入动画结束后，当前页那张卡应为 `TASK-SCALE-X=1.0` 且 `MENU-ALPHA=1.0`，其余卡 `0.97 / 0.0`。
- 若仍残留「相邻卡半聚焦」（`0.986 / 0.5`），则用同一轮的 `PAGE`
  （`mCurrentPage` / `scroll` / `mMaxScroll` / `scrollX`）定位 470px 页偏移的来源，再单独修。

---

## 10. 第三轮日志（23:48 `djdj.log`）：对 §9 修复的判定与真因

### 10.1 判定：**没有修复**

`b` = `idx=1` / `com.termux.nix`（`a` = `idx=0` / `com.f0x1d.logfox`，`c` = `idx=2` / `bin.mt.plus`）：

| 时刻 | 事件 |
| --- | --- |
| 1794239286.588 | `ENTER-RUNNING running=true`，目标 **`idx=1`（b）** |
| 1794239286.590 | `MENU-ALPHA idx=1 alpha=1.0`；栈 `updateCurveInterpolation ← onPageScroll ← OplusStackTaskView.onPageScroll ← updateCurveProperties ← computeScrollHelper` |
| 1794239287.001 | `ENTER-RUNNING running=false`（飞行 413ms，对应原厂 420ms） |

### 10.2 为什么 §9 的改动无效

- §9 的「首帧前补算样式」**生效了**（`idx=1` 曾先被设为 `0.1`），但窗口动画**每帧**都经
  `computeScrollHelper() → updateCurveProperties()` 重新逐卡描边，把飞行中的卡算成聚焦卡，覆盖了它。
- §9 取消吞 `setEnterAnimationRunning(true)` 也挡不住：原厂只在该卡的
  `updatePageOffsets()/updateStackLayoutPageOffsets()` 里跳过，**曲线链路没有这个判断**。
  但这次取消让日志第一次暴露出真目标：`idx=1`。
- 卡带本身**是对齐的**：飞行中 `idx0 = 0`、`idx2 = -1588`（恰为 `-794×2`），只有飞行中的 `idx1`
  被位移（`-1572 → -794`）。此前推断的「470px 页偏移」在静息态并不存在，属误判。

### 10.3 真因

`getEnterAnimator` 的目标写死为 `getTaskViewAt(mCurrentPage + 1)`（`OplusRecentsViewImpl.smali:9044-9052`）。
从主屏进入时 `mCurrentPage = 0` → 它动的是**第二张卡（b）**而非当前卡，且 a/c 完全不参与；
b 在飞行途中被曲线判成聚焦 → 「非聚焦卡突兀聚焦」。

### 10.4 本轮改动

`MainHook.installRecentsEnterAnimHook`（已移除）：
- 逐张构造入场动画：临时把 `mCurrentPage` 置为 `i-1` 再调原厂 `getEnterAnimator(-1)`，
  `i = 0..ENTER_ANIM_CARDS-1`（=3，只覆盖可视的 a/b/c），错峰用**原厂自己的时长**均分，随后原样恢复。

`MainHook.installEnterStyleGuard`（已移除，仅 other-desk）：
- 跟踪 `setEnterAnimationRunning(true/false)` 标记的「飞行中」卡片；
- 飞行中的卡跳过 `OplusStackTaskView.onPageScroll()`（菜单/标题 alpha 的唯一入口），保持出生时的样式；
- 飞行结束 post 一帧，用 `updatePageOffsets()` + `updateCurveProperties()` 收敛整条卡带。

### 10.5 验收标准（第四轮日志）

- `ENTER-RUNNING running=true` 应出现在 `idx=0`（当前卡），而不是 `idx=1`；
- 飞行窗口内（约 700ms）任何卡的 `MENU-ALPHA` 都不应变成 `1.0`，除当前页那张 `idx=0`；
- 动画结束后 `idx=0` 为 `TASK-SCALE-X=1.0` + `MENU-ALPHA=1.0`，`idx>=1` 为 `0.97 / 0.0`。

### 10.6 顺带记录

- `STABLE-ALPHA` 已出现，说明 style-trace 至少挂到第 3 步；`PAGE`/`SCALE-DIM`/`CURVE` 仍无记录，
  推断在 `updateCurveProperties` 那个 hook 处中断。本轮已把 `StyleTrace.page()` 改成逐项容错
  （缺字段填 `-999`/`?`，并打一次 `PAGE-ERR`），下一轮可直接读出 `mCurrentPage/scroll/mMaxScroll/scrollX`。
- `TASK-SCALE-X` 的值来自 `TaskView$3.setValue`（`SCALE_X` 属性动画），并非直接 set；
  菜单/标题 alpha 才是直接 `setAlpha`，故闪烁主要体现在 alpha 上。

---

## 11. 第四轮日志（00:01 `818xj.log`）：残闪定位与实现收敛

### 11.1 判定：仍有一次闪烁，来源是 §10 自己的「飞行结束即补算」

| 时刻 | 事件 |
| --- | --- |
| 1794240072.933 / .935 | 三张卡同时 `ENTER-RUNNING running=true`（逐卡入场生效）|
| 1794240073.355 | `idx=0`（LogFox）飞行结束 → `SCALE=1.0`、`MENU-ALPHA=1.0`（正确：它是当前卡）|
| **1794240073.497** | `idx=1`（MT）飞行结束 **+1ms** → `SCALE=1.0`、`MENU-ALPHA=1.0`（**错**，即残闪）|
| 1794240073.651 | 系统自己把 `idx=1` 改回 `0.9728 / 0.1` |
| 1794240073.988 | 收敛到 `0.97 / 0.0` |

即：残闪来自「在 `setEnterAnimationRunning(false)` 时立刻补算」——那一瞬间卡片列表已重排
（当前应用换到 `idx=0`），但页/滚动状态仍指向旧索引约 150ms，曲线便把这个 `idx=1` 的卡算成聚焦卡。

### 11.2 实现收敛（比 §10 少两类状态机）

§10 用了：飞行集合 + `setEnterAnimationRunning` 钩子 + 逐卡补算 + 窗口结束补算（4 处状态）。
现在只有：**一个冻结开关 + 一次释放补算**。

- `sEnterStyleHold`：在 `createWindowAndAdjacentAnimation` 构建窗口动画时置位；
  `OplusStackTaskView.onPageScroll()`（菜单/标题 alpha 的唯一入口）在冻结期间直接跳过，
  所有卡保持出生样式 → 过渡期间不可能因「焦点漂移」而闪烁。
- 窗口 AnimatorSet 的 `end/cancel` → 解冻 + post 一帧 `updatePageOffsets()` +
  `updateCurveProperties()`（此时状态已稳定）。
- 兜底：`sEnterStyleHoldUntil = now + 动画时长×2`（时长取自动画自身，不引入新常量），
  动画意外不结束时自动解冻，避免描边被永久冻结。
- 新卡首帧补算（`OnPreDrawListener`）保留，并在补算期间用 `sApplyOwnStyle` 穿过冻结。

### 11.3 验收标准（第五轮日志）

- 过渡窗口内（首个 `running=true` → 最后一个 `running=false`）不应出现任何新的
  `MENU-ALPHA = 1.0`，除当前卡外；
- 解冻后只应出现一次补算：`idx=0` = `scale 1.0 / alpha 1.0`，`idx>=1` = `0.97 / 0.0`；
- 若解冻那一次补算仍把 `idx>=1` 写成 `alpha=1.0`，说明解冻时机仍早于页状态稳定，
  下一步改为「等页索引与运行任务一致再解冻」，而不是再调补算时机。

---

## 13. 第五轮：按根因重写（删掉所有抑制，只做页对齐）

### 13.1 为什么此前越修越闪

我一直在改「样式输出」，而问题在「几何输入」：

- 模块自己补的逐卡入场动画直接写 `translationX`（`getEnterAnimator` → `COMPAT_DISMISS_TRANS_X`
  → translation），与布局 `updatePageOffsets()` 算出的槽位互相覆盖。日志里同一毫秒内同一张卡的 X
  在 `0` 与 `-1264` 之间反复写，**差值 1264px 恰好是一页**。
- 聚焦曲线（`updateCurveProperties → onPageScroll → updateCurveInterpolation`）按**页/滚动**状态
  判断「谁是聚焦卡」。页停在旧索引、卡 X 又被动画改过，两者不同源 → 相邻卡被算成聚焦卡。
  实测闪烁帧：`idx0` X=0、`idx1` X=-794（几何都对），曲线仍判 `idx1` 聚焦 → 错在**页状态**。

### 13.2 现在只剩两件事（删掉的比留下的多）

| 删除 | 原因 |
| --- | --- |
| 逐卡入场动画（`getEnterAnimator` + 临时改写 `mCurrentPage`）| 直接写 X，与布局打架，是 X 偏移的直接来源 |
| 冻结开关 `sEnterStyleHold` + 兜底时限 | 抑制描边，不是修复 |
| `onPageScroll` 跳过钩子 | 同上 |
| 新卡首帧补算（`OnPreDrawListener`）/ `applyCardStyle` / 逐卡与窗口结束补算 | 都是「事后补样式」，等于堵 |

| 保留 / 新增 | 作用 |
| --- | --- |
| `installEnterTransitionHook` | 只标记「进入概览的过渡窗口」（`createWindowAndAdjacentAnimation` 构建 → AnimatorSet end/cancel），一个布尔量 |
| `installEnterPageAlign` | 过渡期间若「当前页 ≠ 运行任务所在页」，用原厂 `setCurrentPage(int)` 对齐；带防重入位；过渡结束立即停手 |

### 13.3 为什么这是确定性的

页索引、卡片 X（`updatePageOffsets` 由页算出）、聚焦曲线（由页/滚动算出）三者从此同源：
只要页跟住运行任务，X 和焦点就不可能各说各话。没有阈值、没有时序猜测、没有「补一次」。

### 13.4 验收

- 日志应出现 `ALIGN_PAGE page=<旧> running=<新>`，且过渡结束后不再出现；
- 过渡期间不再出现非运行卡片的 `MENU-ALPHA = 1.0`；
- 过渡结束后 `idx=0`（运行任务）= `1.0 / 1.0`，其余 `0.97 / 0.0`；
- 若 `ALIGN_PAGE` 根本不出现而闪烁仍在，说明滞后的不是页索引而是别的量——那时按日志继续定，
  不再回头改样式。

---

## 14. 第六轮：真正的几何修复（对齐的是「滚动量」）

第五轮日志（`10_11-00-18-41_113dx.log`）判定：

- `ALIGN_PAGE` **一条都没有** → `mCurrentPage` 一直等于运行任务索引，**页号本来就是对的**，上一版是空转。
- 但卡片 X 在 `0/-794/-1588` 与 `-1264/-2058/-2852` 两套值之间每毫秒反复切换（差 **1264px = 一页**），
  另有一个写入者把它们全写成 0。
- `MENU-ALPHA`：`idx0`=1.0（正确）的同时，**`idx1` 在进入后 15ms 拿到 1.0、保持约 470ms**
  （正好是窗口动画时长），然后才回到 0.1 → 0.0；两次进入各一次。

结论：卡的 X = 布局槽位 − **视图滚动量**。滚动量落在相邻页 → 每张卡整体偏一页；聚焦曲线读同一个
滚动量 → 把相邻卡判成聚焦卡。**页号对、滚动量错**，所以对齐页号无效。

修复：过渡期间（`installEnterTransitionHook` 标记的窗口）在 `updatePageOffsets()` 与
`updateCurveProperties()` 两个入口，把滚动量对齐到 `getScrollForPage(runningTaskIndex)`
（原厂几何，无魔数），带防重入位，并打 `ALIGN_SCROLL now=… want=… running=…`；过渡结束立即停手。

验收：出现 `ALIGN_SCROLL`；过渡期间非运行卡的 `MENU-ALPHA` 不再出现 1.0；过渡结束 `idx0`=1.0/1.0，
其余 0.97/0.0。

---

## 15. 真正的根因：`getFocusToNextPageTarget` 返回 `page + 1`（commit `ca51512e` 的副作用）

### 15.1 原厂语义（汇编核实）

`AppToOverviewAnimationProvider.getFocusToNextPageTarget(boolean, RecentsView, OplusBaseSwipeUpHandler)`：

1. 不支持该特性 / 未进入概览 / `pageCount <= 1` → 返回 **-1**（表示「不聚焦」）；
2. `runningTaskIndex ∉ {0, -1}` 或 `currentPage > 0` → **-1**；
3. `page = (running == 0) ? 0 : currentPage`，取 `getTaskViewAt(page)`；
4. `const/4 p2, 0x1` … `add-int/2addr p0, p2` → **返回 `page + 1`**。

即从主屏进入时（`page = 0`）返回 **1** —— 「自动聚焦到下一页」的动画会把焦点与滚动推到**第二张卡**，
那张卡的菜单/标题 alpha 因此被写成 `1.0` 并保持一个动画时长（实测 ~470ms），滚动同时整体偏一页
（**-1264px**）。这就是「非聚焦卡突兀聚焦」的全部来源。

### 15.2 为什么 commit `ca51512e` 没修好

它的 hook 把返回值改成 `runningTaskIndex + 1`：

| runningTaskIndex | 原厂 | commit 的 hook | 结果 |
| --- | --- | --- | --- |
| `0` | 1 | **1** | 与原厂相同 → 闪烁照旧 |
| `-1` | 1 | 0 | 压掉了这一例 |

日志里闪烁的正是 `idx1`，对应 `running == 0` 那条路径 —— 所以「改了个寂寞」。

### 15.3 修复

`installFocusAlignHook` 改为**无条件返回 0**（运行任务所在页，也是下一次任务键实际跳转的
`getTaskViewAt(0)`），并打一行 `FOCUS_TARGET stock=… running=…` 便于下一轮核对。
同时删除此前四轮堆的脚手架：过渡标记、滚动量对齐、聚焦断言（`ASSERT`/`ALIGN_SCROLL` 在两轮日志里
一条都没出，属死代码），`MainHook` 净减约 200 行。

### 15.4 若仍闪烁，说明什么

- 日志里没有 `FOCUS_TARGET` → 这个 hook 没装上（方向转为「为什么装不上」）；
- 有 `FOCUS_TARGET` 但仍闪 → 焦点动画不是唯一写入者，下一步把 `setAlpha` 的调用栈加深到 12 帧
  直接点名，不再推断；
- `scale` 的 0.97 ↔ 1.0（3%）未做断言：该值由原厂按几何算得，不可写死，且肉眼不可见。

---

## 16. 根因上移一层：这个特性在 fallback 下本就不该启用

`AppFeatureUtils.isSupportAutoFocusToNextPageInOverviewState(Z)`（`smali_classes2/.../AppFeatureUtils.smali:5857`）：

```java
if (!OplusGridRecentsConfig.isEnable()) {            // 栈式布局
    if (LauncherAnimConfig.isLevelBOrBPlus()) { if (isQuickSearchBox) { if (hasNavigationBar()) ... } }
    return true;                                      // :cond_0 const/4 p0, 0x1
}
```

即**栈式布局 + 默认动画档 → 直接 true**。该开关本是给「从桌面搜索框进入概览」的，但 fallback 下同样
生效，于是两个症状同源：

- `AppToOverviewAnimationProvider.getFocusToNextPageTarget()` 返回 `page + 1` → 焦点与滚动被推到
  第二张卡（b）；该卡菜单/标题 alpha 写成 1.0（实测约 470ms），滚动偏一页（-1264px）；
- `RecentsActivityCommand.createWindowAndAdjacentAnimation()` 的 `!isSupport(...)` 被判假 →
  原厂自己的逐卡入场动画被整段跳过（卡片「直接就位」）。

### 修复（两层，均只 other-desk）

1. **开关层**（新增 `installFocusNextPageDisable`）：带参重载返回 `false` → 焦点动画不启动，
   `!isSupport(...)` 转真、原厂逐卡入场动画的前提成立。只 hook 带参重载（仅
   `getFocusToNextPageTarget` 与 `createWindowAndAdjacentAnimation` 两处使用），不动无参版
   （`updateCurveProperties` / `TaskThumbnailView` 等仍按原厂逻辑）。
2. **输出层**（`installFocusAlignHook`，兜底）：若开关层未生效，仍把目标纠正为
   `getTaskViewAt(0)`（运行任务所在页）。

### 判定（下一份日志唯一可辨）

| 日志 | 含义 |
| --- | --- |
| `FOCUS_TARGET stock=-1` | 开关层生效：原厂不再给出聚焦页，焦点动画不启动 |
| `FOCUS_TARGET stock=1` + `FOCUS_OVERRIDE running=0` | 开关层未生效，输出层纠正 |
| `MENU-ALPHA idx>=1 = 1.0` 消失 | 症状已消除 |

---

## 17. 第七轮的修复结构（三层；已被方案 G 取代）

| 层 | hook | 作用 | 日志证据 |
| --- | --- | --- | --- |
| 根 | `installFocusNextPageDisable`：`isSupportAutoFocusToNextPageInOverviewState(Z)` → `false`（other-desk）| 「自动聚焦到下一页」不启用 → 焦点动画不启动；`!isSupport(...)` 转真，原厂逐卡入场动画前提成立 | `FOCUS_TARGET stock=-1` |
| 兜底 | `installFocusAlignHook`：目标 → `getTaskViewAt(0)` | 万一开关泄漏，焦点与滚动不被推到第二张卡 | `FOCUS_TARGET stock=1` + `FOCUS_OVERRIDE running=0` |
| 不变量 | `installEnterTransitionHook` + `installEnterFocusInvariant` | 过渡期间每次逐卡样式计算后，把菜单 alpha 写成 1.0（运行任务卡）/ 0.0（其余）；同一帧最后生效 | `ENTER_TRANSITION on` + `INVARIANT idx=… running=… v=…` |

反射目标逐一核对过反编译源码：

- `OplusStackTaskView extends OplusTaskViewImpl`，且**自己声明**了
  `onPageScroll(OplusBasePagedOrientationHandler$ScrollState;Z)V`（public）；
- `OplusTaskViewImpl.updateCurveInterpolation(F)V` 为 private final —— 反射调用合法（`invoke` 走
  declaring class）；
- `RecentsView.getRunningTaskIndex()I`、`AppFeatureUtils.isSupportAutoFocusToNextPageInOverviewState(Z)Z`、
  `AppToOverviewAnimationProvider.getFocusToNextPageTarget(Z,RecentsView,OplusBaseSwipeUpHandler)I` 均存在。

**明确未覆盖**：标题与卡片自身的透明度由另一批 setter 驱动（`setStableAlpha` / `setNonGridAlpha` /
`setDimAlpha` / `TaskThumbnailView.setDimAlpha`），本轮不变量只作用于菜单按钮（日志中闪烁的那个）。
若下一轮观察到标题仍闪，就按同一「运行任务」事实对那批 setter 施同样的不变量。

---

## 18. 最终形态：整个聚焦高亮由模块接管

用户要求「接管整个聚焦效果样式、把原厂那堆无用的 no-op 掉」。实现：

### 18.1 no-op 掉原厂逐卡描边

`OplusStackTaskView.onPageScroll(ScrollState, Z)` —— other-desk 下直接 `return null`。
它是**逐卡**样式（菜单 alpha、曲线缩放、缩略图暗化、图标 alpha）的唯一入口，
跳过它只影响样式，不动布局与位置。

### 18.2 自己实现（值只由「当前页」这个整数决定）

在 `updatePageOffsets()` 与 `updateCurveProperties()` 两个入口之后各写一次（布局帧与滚动帧都覆盖）：

| 属性 | 取值 | 与原厂同源方式 |
| --- | --- | --- |
| 菜单按钮 alpha | `focused ? 1 : 0` | `OplusTaskHeaderView.getMenuBtn().setAlpha(...)` |
| 卡片曲线缩放 | `Companion.getCurveScaleForCurveInterpolation(focused ? 1 : 0)` | 原厂辅助函数 |
| 缩略图暗化 | `setDimAlpha(0.4f * (focused ? 1 : 0))` | 0.4 取自原厂 `setDimAlpha` 处系数 |

`focused = StackPagedViewEx.getCurrentPage()`（整数，不随滚动的小数部分抖动）。

### 18.3 为什么这次不会再闪

- 原厂那条「页/滚动量 → 曲线 → 菜单 alpha」的逐卡链路已整段 no-op，不存在第二个写入者；
- 我们的取值只依赖整数页号，与过渡期抖动的滚动量无关；
- 两个入口任何一方被调用都会把样式重新写到位。

已知取舍：聚焦样式在**翻页边界**切换（而非随手指连续插值），松手落到新页后立即正确。
若需要拖动过程中的连续渐变，可在 `StackPagedViewEx.mIsBeingDragged` 为真时改用滚动插值——
这属于手感优化，与本 bug 无关。

---

## 19. 结论：根治点在「聚焦目标必须等于跳转目标」

### 19.1 返回值的唯一用途（smali 证据）

`AppToOverviewAnimationProvider.focusToNextPageIfNeedInner(...)`：

```java
int target = getFocusToNextPageTarget(fromGesture, recentsView, handler);
if (target == -1) { isFocusToNextPageProcessRunning = false; return -1; }
FocusToNextPageAnim anim = ...; anim.setNextPage(target); anim.setDuration(...); anim.start();
int delta = recentsView.getScrollForPage(target - 1) - recentsView.getScrollForPage(target);
```

即 **`target` 就是「概览要滚到哪一页」**：`target = 1` → 从第 0 页滚到第 1 页（-1264px），
焦点与菜单 alpha 随之落到第 2 张卡（b）。这就是「b 突兀获得聚焦样式」的全部机制。

### 19.2 正确的 target（与跳转同源）

`OverviewCommandHelper.getNextTask(recentsView)`：

```java
TaskView running = recentsView.getRunningTaskView();
if (running == null) return recentsView.getTaskViewAt(0);   // 从主屏进入 → 第 0 张
TaskView next = recentsView.getNextTaskView();
return next != null ? next : running;
```

- 从主屏进入（`getRunningTaskView() == null`）→ 跳转目标 = **第 0 张 = 页 0**；
- 有运行任务 → 跳转目标 = `getNextTaskView()`，与原厂 `page + 1` 一致。

`ca51512e` 的 hook 返回 `runningTaskIndex + 1`：`running = -1` 时为 0（正确），
**`running = 0` 时仍为 1（等于原厂，等于没修）** —— 这就是它的副作用。

### 19.3 采用方案（两层，均 other-desk）

1. `installFocusAlignHook`：仅当「无运行任务视图」时把 target 纠正为 **0**，其余交还原厂 ——
   与 `getNextTask()` 严格同源；
2. `installFocusNextPageDisable`：`isSupportAutoFocusToNextPageInOverviewState(Z)` → `false`，
   让该动画根本不启动（兜底，同时让原厂逐卡入场动画的前提成立）。

自造的样式接管（`onPageScroll` no-op + 自行写 alpha/scale/dim）已整体删除；它引入的
「读 `Companion` 静态字段导致框架类提前初始化」正是 00:51 崩溃的根因。

### 19.4 验证

| 日志 | 期望 |
| --- | --- |
| `FOCUS_TARGET stock=…` | 开关生效时为 `-1` |
| `FOCUS_OVERRIDE hasRunning=false` | 从主屏进入时走纠正分支，返回 0 |
| `MENU-ALPHA` | 不再出现 `idx>=1 = 1.0` |

---

## 20. 残余症状：非聚焦卡带着聚焦样式且**永不消失**

> **本节结论已被 §21 取代。** 当时以为成因是「入场动画把该卡排除出逐卡样式计算」，据此加的
> `installEnterSettle`（占位拦截 + 标志翻回 `false` 时补算）**实测无效**。真正成因是
> §21 描述的「插值输入用了静态槽位」；`installEnterSettle` 已从模块中删除。

### 20.1 机制

关掉 `isSupportAutoFocusToNextPageInOverviewState(Z)` 之后，`createWindowAndAdjacentAnimation` 里
`!isSupport(...)` 这个**前提反过来成立**，于是原厂自己的「下一张卡入场动画」被启用，目标写死

```java
getEnterAnimator(mCurrentPage /* = 0 */) → 目标 = getTaskViewAt(mCurrentPage + 1) = idx 1 = b
```

入场期间原厂用 `OplusTaskViewImpl.setEnterAnimationRunning(true)` 让该卡
**退出逐卡样式计算**（`OplusRecentsViewImpl:26810/27924` 的 `goto_10`，`updatePageOffsets` 同样跳过），
动画结束置 `false`。但如果此后**没有任何滚动/布局再触发一次样式计算**（用户不动手指就没有
`computeScroll`），这张卡就永远停在「入场前」的样式 —— 默认即**聚焦外观**：菜单按钮常驻、标题常亮。
「永不消失」正是指这个。

### 20.2 修法

`installEnterSettle`：hook `OplusTaskViewImpl.setEnterAnimationRunning(Z)`，在**翻回 `false`** 时
`post` 一次 `updatePageOffsets()` + `updateCurveProperties()`。此时上游状态已是正确的
（当前页 = 0），整条卡带被按「聚焦卡 = 屏幕中间那张」重画一遍，b 的样式随之消失。

不变式：**聚焦样式只能出现在当前页（屏幕中间）那张卡上**；其它卡在入场结束后一律回落。

---

## 21. 方案 G：把样式输入换成「卡片真实位置」

### 21.1 唯一产地（smali 证据）

`OplusRecentsViewImpl.updateCurveProperties()`（`:25502`）里的逐卡循环：

```java
for (int i = 0; i < pageCount; i++) {
    View v = getPageAt(i);
    if (v == null) continue;
    if (v instanceof TaskView) taskViewCount++;
    if (taskViewCount == 2 && RecentsViewAnimUtil.isLaunchFromButtonRunning()
            && AppFeatureUtils.isSupportAutoFocusToNextPageInOverviewState()) {
        mScrollState.updateInterpolation(profile, getChildStart(v));               // 静态槽位
    } else {
        mScrollState.updateInterpolation(profile, getChildStartWithTranslation(v)); // 真实位置
    }
    ((PageCallbacks) v).onPageScroll(mScrollState, grid);   // :25660
}
```

`ScrollState.updateInterpolation(profile, childStart)`：

```
linearInterpolation = |screenCenter − (halfPageSize + childStart)|
                      / (halfScreenSize + (1 − edgeScaleDownFactor) × halfPageSize)
```

→ **0 = 这张卡正在屏幕中间（聚焦），1 = 差一整页**。

消费链：`onPageScroll` 读 `getLinearInterpolation()`（`:6066`）→ `updateCurveInterpolation`（`:3387`，
**private final 已内联**）→ `getCurveAlphaForCurveInterpolation`（`:3394`）→ 菜单按钮 `setAlpha`（`:3402`）。

### 21.2 病灶

第一个分支把「这张卡**应有的槽位**」当成了「它**实际所在的位置**」。`mCurrentPage = 0` 时第 2 张卡的
槽位恰好落在屏幕中心 → 它每一轮都得到插值 0 → 菜单/标题常亮不回落；而位置由 `mCurrentPage` 决定，
所以画面不偏移。「非聚焦卡永久带聚焦样式」= 这个分支的产物，不是"卡住了"。

### 21.3 修法

`installScrollInterpolationSource`：在每张卡的 `OplusTaskViewImpl.onPageScroll()` 入口
（`OplusStackTaskView` 把 `super` 调用放在方法最后、之前不写样式，故此处是最早的点），用**原厂同一个公式**
`ScrollState.updateInterpolation(profile, getChildStartWithTranslation(card))` 重算一次插值。
公式、曲线、alpha/scale/dim 全部仍归原厂，模块只把输入换回事实。

安全性：常态分支本就是同一个值 → 重算**幂等**、不改变原有手感；仅 other-desk；任一反射失败即静默跳过、
退回原厂输入；不写任何样式值、不接管任何 setter。

### 21.4 落到当前实现（仅 other-desk 生效）

| 现行方法（`RecentsKeyFixes`） | 作用 |
| --- | --- |
| `alignOverviewFocusTarget` | 聚焦目标 = 任务键真实跳转目标（无运行任务时为第 0 页） |
| `fixCardInsertionInterpolation` | **G**：逐卡样式输入 = 卡片真实位置 |

> 此前几轮的迭代（关掉「自动聚焦到下一页」开关、生成 / 抑制逐卡入场动画、诊断探针）均已移除：
> 关开关会同时改掉原厂逐卡入场动画成立的前提，入场动画则直接写 `translationX`、与布局槽位互相覆盖。
> 「整个聚焦高亮由模块接管」（§18）也曾实现过，最终由方案 G 取代——只纠正输入、不接管输出。

---

## 22. 「与默认桌面一样的进入动画」：结构结论

### 22.1 分流点（决定机制，不是参数）

`RecentsActivityCommand.run()`（`:1011-1013`）以 `instanceof LauncherActivityInterface` 分流：

| 场景 | 分支 | 机制 | 动画来源 |
| --- | --- | --- | --- |
| 系统桌面**是**默认 Home | `LauncherActivityInterface` | `oplusSwitchToRecentsIfVisible()`（`OplusBaseActivityInterface:1857`）→ `StateManager.goToState(OVERVIEW)`（`LauncherActivityInterface:1556`） | **进程内状态转换**：DragLayer / home / 壁纸 / Dock 整体参与 |
| 系统桌面**不是**默认 Home（other-desk） | `FallbackActivityInterface` | `switchToRecentsIfVisible()` **恒返回 false**（`FallbackActivityInterface:692-695`）→ `registerAndStartActivity`（`:1196`）启动 `RecentsActivity` | 只剩**远程窗口动画**（`createWindowAnimation`）→ 观感即「淡化 + 缩放深入」 |

`mActivityInterface` 在 `OverviewComponentObserver` 初始化时按**字段** `mIsDefaultHome` 选定
（home 分支 `:1107-1135` / fallback 分支 `:1193-1203`），因此 hook `isDefaultHome()` getter
**改不到它**。强行切到 home 分支需要真的存在 `Launcher` 实例 —— 第三方桌面为 Home 时不存在，
概览会直接打不开。**故「与默认桌面一模一样」在架构上不可达**，可达到的上限是：
把 other-desk 下被额外削弱的窗口动画补回原样。

### 22.2 other-desk 额外削弱的门控（读的是 `isOtherDesk` 真定义）

| 门控 | 位置 | other-desk 行为 |
| --- | --- | --- |
| `DockViewController.gotoState` | `:1889-1892` | 方向不匹配时**直接 return null** → Dock 不参与转场 |
| `PanoramaUtil.supportPanoramic()` / `supportPanoramicFold()` | `:299` / `:335` | 恒 false → 全景（连续横滑）概览被禁 |
| `LauncherAnimationRunner$AnimationResult.finish()` | `:178` | 选另一条收尾 Runnable |
| `OverviewComponentObserver.updateOverviewTargets` | `:935` | 写 `panoramic_forced_disable_state` |
| `RecentsActivityCommand.createWindowAndAdjacentAnimation` | `:192-196` | `isHomeAndOverviewSame()==false` → 跳过逐卡 `getEnterAnimator`（窗口动画里的"逐卡飞入"段） |

真定义只有 `OverviewComponentObserver.isOtherDesk()`（`:559`，比较默认 Home 包名与
`com.android.launcher`）；`OplusStackRecentsConfig.isOtherDesk()`（`:221`）只是转发。
**模块此前只 hook 了转发版**，上表前 4 处一直未被修正。

### 22.3 尝试过并已回退的修法

曾按上述思路 hook `OverviewComponentObserver.isOtherDesk()` 的**真定义**（`installOtherDeskIdentityHook`
+ 安装期采样 `sOtherDesk`），让 §22.2 表中前 4 处也走默认桌面分支。该方案**已回退**：真定义同时也是
「本桌面是否为默认 Home」这条判断本身，把它固定为 `false` 会让 Home 键 / `goHome` 的 intent 选取出错，
实测 Home 键直接失效。

现行做法是只 hook 转发版 `OplusStackRecentsConfig.isOtherDesk()`（见 `RecentsKeyFixes.install`），
因此 §22.2 表前 4 处门控保持原厂行为。这 4 处对应的观感差异（Dock、全景、收尾回调）是否值得单独补齐，
需要独立评估，**不应再用「伪装真定义」的方式**。

### 22.4 窗口动画参数（供后续比对）

| 项 | 值 | 证据 |
| --- | --- | --- |
| 时长 | 480ms；grid 概览 300ms；level-B 动画档 250ms | `AppToOverviewAnimationProvider:22-28,527-548` |
| 曲线 | `TOUCH_RESPONSE_INTERPOLATOR`（depth/home）、`TOUCH_DEFAULT_SECOND_TASK_INTERPOLATOR`（PROGRESS/task）、`DEACCEL_2`（alpha 映射），外层 `RecordInputInterpolator` | 同文件 `:1309,1425,728,749,1670` |
| 关闭 app 的 leash alpha | `mapToRange(progress,0,1,1,1,DEACCEL_2)` | `:730-762` |
| home 目标 alpha 起值 | `isDefaultHome ? 1.0 : 0.0` → 1.0 | `:1556-1612` |
| 缩放 | `getMaxScaleForFullScreen()` → 1.0，名 `recent_scale_in_button_to_recent`（受**无参** `isSupportAutoFocusToNextPageInOverviewState()` 约束） | `:1635-1694` |
| 逐卡 | `OplusBaseTaskViewSimulator.addAppToOverviewAnim`；`fromGesture` 时附加 `mRecentsViewTransY` 0→1 | `:1695`、`OplusBaseTaskViewSimulator:488-518` |
| 通知/快捷面板可见时 | 追加 `setStartDelay(200ms)` | `RecentsActivityCommand$1:37-41` |
