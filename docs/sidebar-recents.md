# 智能侧边栏「最近应用」

- 分析对象
  - 智能侧边栏 `com.coloros.smartsidebar` 16.16.2（versionCode 160016002），反编译工程
    `/storage/emulated/0/MT2/apks/智能侧边栏_16.16.2_src/`。该包保留源码原名。
- 运行环境：ColorOS 16 / Android 16，系统桌面 `com.android.launcher` 16.4.5
- 标注：**实证** = 反编译代码中确有此分支 / 调用 / 常量；**推断** = 由代码路径推导出的运行时后果

## 0. 结论

1. **默认 Home 判定跨进程统一**。系统桌面进程内用其私有 `OverviewComponentObserver.isOtherDesk()`；
   智能侧边栏进程内没有这套实现，模块改为解析 `ACTION_MAIN + CATEGORY_HOME` 的默认结果
   （`ThirdPartyHome`），只要不是系统桌面即视为第三方桌面。
2. **智能侧边栏最近应用来自使用统计**（实证）。模块在 `RecentAppSource` 的两个出口上按
   `EntryBean.getPkg()` 剔除当前默认 Home 包名。

## 1. 默认 Home 判定

系统桌面侧沿用 `OverviewComponentObserver.isOtherDesk()`。智能侧边栏进程内改用平台口径：

```java
Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
ResolveInfo info = context.getPackageManager()
        .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
// info.activityInfo.packageName 即当前默认 Home
```

判据为「解析到的包名存在且不是 `com.android.launcher`」。解析失败一律按「非第三方桌面」处理。

旁证：智能侧边栏自身也用同样的口径判断 Home。`EdgePanelUtils.isHomeVisible(List<OplusAppInfo>)`
（`smali/com/coloros/edgepanel/utils/EdgePanelUtils.smali:9027`）比较 `Consts.PKG_LAUNCHER`，该常量在
部分机型上取 `com.oppo.launcher`，本机型为 `com.android.launcher`。

## 2. 智能侧边栏「最近活动应用」

### 2.1 数据源（实证）

`RecentAppSource$Companion`（`smali/com/oplus/smartsidebar/panelview/edgepanel/recentapp/`）：

- `loadRecentFromUsageStats(int)`（`:3576`）：窗口 `[now - 24h, now]`，用
  `UsageStatsManager.queryAndAggregateUsageStats()` 取聚合用量，再由
  `buildUsageStatsAppendContext()` / `appendRecentFromUsageStats()` 生成列表；
- 剔除列表的一部分来自静态常量 `mCustomUnSupportedPackageNames`（`RecentAppSource.<clinit>`），
  内容为「侧边栏自身包名」与 `com.android.documentsui`；
- 列表元素是 `EntryBean`，包名由 `getPkg()` 给出（`models/beans/EntryBean.smali:854`）。

### 2.2 出口与消费链（实证）

| 方法 | 说明 |
| --- | --- |
| `requestRecentAppDataFromService(boolean)`（`:5052`） | 正常取数；命中缓存则直接返回，否则查使用统计并写缓存 |
| `requestRecentAppDataFromDefault(int)`（`:4524`） | 用量不足时的默认 / 兜底列表 |

消费链有两条，都以 `requestRecentAppDataFromService()` 为数据来源：

- `EntryBeanHelper.requestRecentAppData(boolean)`（`EntryBeanHelper.smali:4578`）→
  `RecentDataHandlerImpl.subscribeRecentDataList()` → `RecentAppDataControl.onSystemRecentListEmitted()`
  → `emitRecentList()`；
- `RecentAppDataControl.buildSyncDisplayListForSlotCount(int)`（`:4283`）直接取用其返回值。

## 3. 智能侧边栏的 Hook 点

`SidebarRecentsFixes.install()` 挂上述两个出口，放行原实现后按 `EntryBean.getPkg()` 剔除当前默认
Home 包名，返回新列表。上下文取自侧边栏自己的静态 `App` 类的 `sContext` 字段，仅用于解析默认 Home。

也可以改用应用自带的剔除列表（`mCustomUnSupportedPackageNames`），但那需要再挂静态初始化或访问器，
且只覆盖使用统计这一条来源；过滤出口可以一次覆盖正常取数与兜底取数两条来源。

## 4. 证据索引

| 结论 | 证据 |
| --- | --- |
| 最近应用数据源与出口 | `.../recentapp/RecentAppSource$Companion.smali:3576,5052,4524` |
| 内置剔除列表 | `.../recentapp/RecentAppSource.smali:88-113,255` |
| 列表元素包名 | `.../data/entrybeans/models/beans/EntryBean.smali:854` |
| 侧边栏 Context | `smali/com/coloros/common/App.smali:12` |
