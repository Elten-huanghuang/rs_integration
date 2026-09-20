# 合成规划后台进度 HUD 可行性与实现设计

## 1. 结论

在现有架构上实现“后台规划进度卡片”是可行的，并且可以不改变机器并发、配方选择和合成执行逻辑。

这不是单纯修改 actionbar 文案的功能。当前规划链只有“排队/运行/完成/失败”的生命周期，没有可供客户端读取的实时进度快照；需要新增一层线程安全的规划状态发布、服务端到客户端同步和客户端展示。

建议将它作为独立的“规划进度”功能实现，不直接复用执行阶段的 `CraftProgressSnapshot`。执行 HUD 的渲染风格和部分展示组件可以复用，但两者的请求 ID、生命周期和数据含义不同。

## 2. 当前代码结构

### 2.1 后台规划

`AsyncPlanningCoordinator` 创建固定大小的 planner 线程池。后台任务只读取服务器线程捕获的不可变 `PlanningSnapshot`，完成后通过服务器 executor 执行校验、提交或回滚：

- [AsyncPlanningCoordinator.java](../src/main/java/com/huanghuang/rsintegration/crafting/planning/AsyncPlanningCoordinator.java)
- [AsyncPurePlanningService.java](../src/main/java/com/huanghuang/rsintegration/crafting/planning/AsyncPurePlanningService.java)
- [PlanRequestService.java](../src/main/java/com/huanghuang/rsintegration/crafting/planning/PlanRequestService.java)

规划请求按玩家 UUID 管理。新请求会取消该玩家的旧请求；相同请求可以共享一次昂贵的后台计算，但回调会替换为最新请求的回调。

### 2.2 规划阶段和预算

`PlanningSession` 已经提供以下生命周期信息：

- `requestGeneration`
- `startedNanos`
- `deadlineNanos`
- `phase`
- 取消状态和超时检查

现有阶段枚举包括：

```text
QUEUED
PREPARATION
DEPENDENCY_PROJECTION
SMITHING_STATE_BINDING
INVENTORY_BINDING
DEMAND_TREE
RECURSIVE_SEARCH
RESULT_HANDOFF
UNKNOWN
```

实际异步路径目前主要设置 `PREPARATION`、`DEPENDENCY_PROJECTION`、`DEMAND_TREE` 和 `RECURSIVE_SEARCH`。部分阶段枚举已经存在，但并没有在所有路径中使用，因此不能只做翻译映射，还需要补齐阶段切换点。

### 2.3 规划入口

JEI 发起请求时，客户端目前只显示一次 actionbar 消息，然后向服务端发送 `GenericCraftPacket`：

- [RecipeGuiLayoutsMixin.java](../src/main/java/com/huanghuang/rsintegration/mixin/jei/RecipeGuiLayoutsMixin.java)

服务端在 `GenericCraftPacket` 中捕获 `PlanningSnapshot`，满足条件时调用 `PlanRequestService.submitRouted(...)`，最终发送一个完整的 `PlanResponsePacket`：

- [GenericCraftPacket.java](../src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java)
- [PlanResponsePacket.java](../src/main/java/com/huanghuang/rsintegration/crafting/plan/PlanResponsePacket.java)

当前没有中间的规划进度网络包。

### 2.4 现有执行进度 HUD

`AsyncCraftChain` 是已经接受合成后的执行状态机，不是预览规划器。执行链启动后发送 `CraftStartedPacket`，之后通过 `CraftProgressPacket` 和增量包同步节点状态：

- [AsyncCraftChain.java](../src/main/java/com/huanghuang/rsintegration/crafting/AsyncCraftChain.java)
- [CraftStartedPacket.java](../src/main/java/com/huanghuang/rsintegration/crafting/batch/CraftStartedPacket.java)
- [CraftProgressPublisher.java](../src/main/java/com/huanghuang/rsintegration/crafting/batch/CraftProgressPublisher.java)

客户端的 `CraftProgressTracker`、`CraftProgressOverlay` 和 `CraftProgressScreen` 只消费执行阶段的 `CraftProgressSnapshot`：

- [CraftProgressTracker.java](../src/main/java/com/huanghuang/rsintegration/crafting/CraftProgressTracker.java)
- [CraftProgressOverlay.java](../src/main/java/com/huanghuang/rsintegration/crafting/CraftProgressOverlay.java)
- [CraftProgressPresentation.java](../src/main/java/com/huanghuang/rsintegration/crafting/CraftProgressPresentation.java)

因此，规划开始时尚未创建 `craftId`，不能直接把规划状态伪装成执行任务。

## 3. 当前缺失的能力

### 3.1 没有线程安全的实时快照

`PlanningSession.phase` 是 `volatile`，但 `PlanningSession` 只存在于后台计算方法内部，没有注册到可查询的服务中。`AsyncPlanningCoordinator` 的活动请求表也是私有的，没有进度读取接口。

需要新增一个不可变快照，例如：

```java
PlanningProgressSnapshot {
    requestId;
    requestGeneration;
    recipeId;
    phase;
    state;
    processedNodes;
    discoveredNodes;
    totalNodes;       // 未知时为 -1
    expandedStates;
    startedNanos;
    lastUpdateNanos;
    detail;
}
```

由 `AtomicReference<PlanningProgressSnapshot>` 发布，客户端或服务器 tick 只读取最近一次完整快照。

### 3.2 planner 只有最终计数

`PureRecipePlanner` 内部维护：

- `expandedStates`
- `backtracks`
- `memoHits`

这些值只在最终 `Result` 中返回。`PureDemandTreeInspector` 的 `visitedNodes` 也只在最终 `Result` 中返回。要显示实时数值，必须在搜索循环和需求树遍历中增加低频快照更新。

不能在每个节点处理后刷新 GUI 或发送网络包。

### 3.3 没有规划取消协议

服务器内部已有 `PlanRequestService.forget(UUID)` 和 `AsyncPlanningCoordinator.cancel(UUID)`，主要用于请求替换、玩家离线和服务关闭。

如果 HUD 提供取消按钮，还需要：

1. 新增带 `requestId` 或 `requestGeneration` 的 C2S 取消包。
2. 服务端校验该请求确实属于发送者且仍是当前请求。
3. 取消后发布终态，避免客户端永久停留在“后台计算中”。

## 4. 推荐的数据模型

### 4.1 状态

建议将规划状态定义为：

```text
QUEUED       已提交，等待 planner worker
RUNNING      正在执行某个阶段
COMPLETING   规划完成，正在服务器线程校验并生成响应
SUCCEEDED    已生成计划
FAILED       规划失败
CANCELLED    玩家或系统取消
TIMED_OUT    超出规划预算
STALE        请求已被更新的请求替代
```

`STALE` 可以只作为服务端内部状态，客户端通常直接删除旧卡片。

### 4.2 阶段映射

推荐的玩家可见阶段：

| UI 阶段 | 现有规划阶段 | 说明 |
| --- | --- | --- |
| 收集目标配方 | `PREPARATION` | 从不可变快照准备目标和库存视图 |
| 裁剪无关依赖 | `DEPENDENCY_PROJECTION` | 构造目标依赖子图 |
| 分析中间节点 | `DEMAND_TREE` | 检查递归需求和可达性 |
| 检查库存与 NBT | `INVENTORY_BINDING`、搜索期间的匹配 | 需要补充更细的埋点，不能只依赖当前枚举 |
| 生成合成执行计划 | `RECURSIVE_SEARCH`、`RESULT_HANDOFF` | 完成搜索并转换为最终计划 |

如果某个阶段无法提供可靠计数，应显示阶段名称和动画，不显示伪百分比。

### 4.3 计数语义

建议至少同步以下字段：

- `processedNodes`：已经检查过的需求/图节点数量。
- `discoveredNodes`：目前已发现的节点数量。
- `expandedStates`：搜索状态展开数量。
- `totalNodes`：只有规划器明确知道总任务量时才填充。
- `detail`：当前配方 ID、库存匹配、超时阶段或失败原因。

所有计数都应单调递增；如果搜索回溯，不应把计数回退。

## 5. 百分比规则

### 5.1 不应使用的计算

以下计算不能作为“规划完成百分比”：

```text
expandedStates / maxSearchStates
visitedNodes / demandMaxNodes
elapsedTime / timeout
```

`maxSearchStates` 和 `demandMaxNodes` 是安全预算，不是总工作量。递归分支、NBT 匹配和回溯可能动态增加工作，时间比例也无法反映不同配方的实际复杂度。

### 5.2 推荐显示

默认显示：

```text
正在规划合成
阶段：分析中间节点
已处理节点：842
已发现节点：879
搜索状态：1,248
后台计算中，其他请求仍可继续处理
```

只有当规划器已经建立稳定的任务总量时，才显示：

```text
进度：62%
```

阶段切换时百分比不得回退。无法保证这一点时使用循环动画或不定进度条。

## 6. 网络与客户端设计

### 6.1 新增独立进度包

建议新增 `PlanningProgressPacket`，不要把中间状态塞入最终 `PlanResponsePacket`。

包中至少包含：

- `requestId`
- `requestGeneration`
- 目标配方或目标显示信息
- 状态和阶段
- 计数器
- 可选的失败 key/detail

包应设置字段和字符串长度上限，沿用现有计划包的防御性解码规则。

### 6.2 发送频率

后台线程只更新原子快照。网络发送应由服务器线程以约 100～250ms 的频率轮询，或者在已有服务器 tick 调度中发送。

不要让 planner worker 直接操作 Minecraft 网络或 GUI 对象。

建议：

- 状态变化立即发送一次。
- 普通运行状态按 100～250ms 节流。
- 完成、失败、取消、超时各发送一次终态。
- 新请求替代旧请求时发送旧请求终止或让客户端按 generation 丢弃。

### 6.3 客户端 tracker

建议新增独立的 `PlanningProgressTracker`，按 `requestId` 保存规划卡片。

它不应写入 `CraftProgressTracker`，原因是：

- 规划请求还没有 `craftId`。
- 规划可能最终失败，不会创建执行任务。
- 同一个玩家只能有当前规划请求，但执行阶段可以同时有多个 craft。
- 两种进度的百分比和终态语义不同。

## 7. 失败、超时和取消

### 7.1 规划完成

规划成功后建议按以下顺序处理：

1. 发送 `COMPLETING` 或“规划完成，正在启动合成”的短暂状态。
2. 服务器线程完成状态重新校验和最终响应生成。
3. 发送最终 `PlanResponsePacket`。
4. 客户端移除规划卡片，或者短暂显示成功过渡状态。

### 7.2 规划失败

最终失败应优先显示结构化原因，例如：

- 缺少材料及数量。
- 没有绑定所需机器。
- NBT 或物品变体不匹配。
- planner 忙或请求被拒绝。
- 特殊配方只能在主线程规划。

缺少材料信息通常只能在需求树或 planner 产生诊断结果后确定，不能在规划刚开始时提前承诺具体材料。

### 7.3 超时

超时应保留最后一个快照并显示：

```text
规划仍在运行，已处理节点：xxx
规划阶段：递归搜索
```

如果服务端已经终止任务，则状态应改为 `TIMED_OUT`；如果只是 UI 等待超时但后台任务仍然存在，必须明确区分“界面等待超时”和“规划任务已终止”。

### 7.4 取消

取消后应：

- 设置后台请求的取消标志。
- 中断 worker future。
- 发布 `CANCELLED` 终态。
- 阻止旧回调覆盖新请求。

现有 `requestGeneration` 和 coordinator 的旧请求取消机制可以作为基础。

## 8. 需要修改的模块

预计涉及以下范围：

1. `crafting/planning`：增加进度快照、状态注册表和发布接口。
2. `AsyncPlanningCoordinator`：保存当前请求的进度引用，并处理排队、取消和终态。
3. `PlanningSession`：增加计数更新和快照发布能力。
4. `AsyncPurePlanningService`、`PureRecipePlanner`、`PureDemandTreeInspector`：在阶段和循环关键点更新计数。
5. `PlanRequestService`、`GenericCraftPacket`：建立 requestId/generation 到进度状态的关联。
6. 网络层：新增规划进度包、包 ID、编解码和客户端 handler。
7. 客户端：增加规划 tracker 和 HUD 卡片；可以复用 `CraftProgressOverlay` 的颜色、布局和渲染工具，但不要复用执行快照类型。
8. JEI 入口：把一次性的后台计算 actionbar 文案替换为启动规划卡片，保留最终计划界面行为。
9. 测试：覆盖快照发布、包编解码、请求替换、取消、超时和客户端终态。

不需要修改：

- 机器并发窗口。
- `AsyncCraftChain` 的节点调度逻辑。
- 材料提取、NBT 匹配和机器执行顺序。
- 已有执行阶段 `CraftProgressSnapshot` 的协议。

## 9. 特殊路径和限制

### 9.1 主线程专用配方

`AsyncPurePlanningService` 对 `mainThreadOnly` 请求会直接回退到服务器线程。这类请求不能声称正在后台规划，应显示单独的“正在检查特殊配方”状态，或者继续使用简化提示。

### 9.2 规划前的服务器线程工作

`GenericCraftPacket` 在提交异步任务之前仍会进行配方解析、库存快照捕获、绑定检查和部分直接规划。这些耗时不属于 planner worker 内部，不能仅靠后台规划快照覆盖。

如果需要完整覆盖用户从点击到最终结果的时间，必须额外定义“请求准备阶段”；否则 HUD 应明确表示它只统计后台 planner 阶段。

### 9.3 共享请求和旧响应

相同请求可能共享一次后台计算，但回调使用最新请求上下文。因此进度状态必须携带 requestId 或 generation，客户端不能只按配方 ID判断是否为当前请求。

## 10. 测试清单

### 单元测试

- 快照发布在多线程下不会读到部分更新。
- 计数单调递增，负数和溢出被限制。
- 阶段切换不会造成非法回退。
- `requestGeneration` 不匹配时丢弃更新。
- 规划进度包正常往返编解码。
- 超长字符串、超大计数和非法枚举被拒绝。

### 生命周期测试

- 排队请求能显示 `QUEUED`。
- 新请求替代旧请求后，旧请求不能覆盖新请求。
- 取消后一定收到 `CANCELLED` 或可被客户端安全清理的终态。
- 超时后不会生成不完整的执行计划。
- planner 忙、同步回退和主线程专用路径不会遗留进度卡片。

### UI 测试

- 未知总量显示不定进度动画，而不是错误百分比。
- 阶段名称、计数和目标名称在窄窗口不重叠。
- 规划完成后卡片能平滑切换到最终计划界面。
- 失败原因优先显示结构化缺料或绑定原因。
- 断线、切换服务器和客户端重连后不会保留旧请求。

## 11. 推荐实现顺序

1. 先实现服务端 `PlanningProgressSnapshot` 和阶段/计数埋点，不做 UI。
2. 增加单元测试，确认计数、取消、超时和旧请求丢弃语义。
3. 增加 S2C 进度包和客户端 tracker，只显示阶段、计数和动画。
4. 将 actionbar 提示替换为规划卡片。
5. 最后再评估是否有某些规划路径能提供可靠的总量并启用真实百分比。

## 12. 最终判断

“阶段 + 已处理数量 + 动画进度 + 明确失败/超时状态”在当前代码基础上可稳定实现，风险主要集中在新增状态同步和请求生命周期管理。

“已检查配方数直接作为合成完成百分比”不可行，也不应实现。真实百分比只有在规划器能够证明总任务量稳定时才有意义。

该功能不会自动修复材料依赖聚合、叶子材料收集或中间节点选择问题；它只改善后台规划过程的可见性。
