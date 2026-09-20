# 合成规划后台进度 HUD 可行性与实现设计

> 复审日期：2026-09-21
>
> 代码基线：`36af89e`（`Scale parallel batch windows by worker capacity`）
>
> 状态：基础版已实现；节点计数、真实百分比和取消按钮留作后续增强

## 当前实现状态

基础版已经按本设计落地，范围如下：

- JEI、EMI、计划界面刷新和 `PlanPreviewClient` 统一使用会话级非零 `requestId`。
- 服务端使用请求级线程安全快照，覆盖配方目录等待、后台队列、依赖裁剪、需求树、递归搜索、typed resolver 和最终响应校验。
- 新增独立 S2C 进度包和客户端 tracker；旧请求、迟到包和断线状态不会覆盖当前请求。
- HUD 为底部中央 actionbar 位置的紧凑 `196 × 27` GUI 像素横条，只显示状态、真实阶段、真实耗时和不定进度动画；面板底部保留 `65` GUI 像素，避免与生命值、护甲值和快捷栏重叠。
- 背景使用原生 `GuiGraphics.fill` 绘制完全不透明的纯黑色，不依赖纹理、渐变或透明混合背景。
- 没有打开界面时在 `RenderGuiEvent.Post` 绘制；打开物品栏、终端等 `Screen` 时改在低优先级的 `ScreenEvent.Render.Post` 绘制。两条入口共享同一个渲染方法，并在 Screen 存在时跳过 HUD 入口，避免重复绘制。
- 面板内部使用 `Z = 500`；真正保证其位于物品栏等界面上方的是 `Screen.Render.Post` 的绘制时机，而不是单纯继续增大 Z 值。
- 成功、普通失败和规划超时使用不同终态；失败时显示服务端已有的实际错误消息。
- 匹配的正常计划响应到达客户端后立即清除成功卡片，避免遮挡随后打开或刷新的递归合成界面；若界面响应异常未到达，成功终态也只回退显示 `400ms`。失败和超时仍保留 `3s`，确保错误原因可读。
- 未实现节点计数和百分比，因为当前递归规划工作总量仍会动态变化。

下文第 2～12 节保留实现前的审计和设计依据，便于后续增加节点计数或取消协议时继续使用。

## 1. 结论

在现有架构上实现“后台规划进度卡片”是可行的，并且可以不改变机器并发、配方选择和合成执行逻辑。

这不是单纯修改 actionbar 文案的功能。当前代码没有可供客户端读取的实时规划快照；需要新增线程安全的规划状态发布、服务端到客户端同步和客户端展示。

复审后需要扩大“规划生命周期”的定义。一次预览可能依次经过配方目录预热队列、服务器线程快照准备、后台纯规划、typed preview 准入队列、服务器线程 typed resolver、后台响应封装和最终响应发送。只给 `PureRecipePlanner` 加计数会留下多段无状态空窗。

建议将它作为独立的“规划进度”功能实现，不直接复用执行阶段的 `CraftProgressSnapshot`。执行 HUD 的渲染风格和部分展示组件可以复用，但两者的请求 ID、生命周期和数据含义不同。

## 2. 当前代码结构

### 2.1 后台规划

`AsyncPlanningCoordinator` 创建固定大小的 planner 线程池。后台任务只读取服务器线程捕获的不可变 `PlanningSnapshot`，完成后通过服务器 executor 执行校验、提交或回滚：

- [AsyncPlanningCoordinator.java](../src/main/java/com/huanghuang/rsintegration/crafting/planning/AsyncPlanningCoordinator.java)
- [AsyncPurePlanningService.java](../src/main/java/com/huanghuang/rsintegration/crafting/planning/AsyncPurePlanningService.java)
- [PlanRequestService.java](../src/main/java/com/huanghuang/rsintegration/crafting/planning/PlanRequestService.java)

规划请求按玩家 UUID 管理。新请求会取消该玩家在 `AsyncPlanningCoordinator` 中的旧后台任务；相同纯规划请求可以共享一次昂贵计算，但回调会替换为最新请求的回调。

这层协调器只覆盖当前提交到 planner 线程池的一个任务。一次完整预览还可能先后提交纯规划任务和 `AsyncPlanResponseService` 响应封装任务，中间也可能转入服务器线程的 typed resolver。因此完整 HUD 状态不能只由 `AsyncPlanningCoordinator.Request` 持有。

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

实际纯规划路径目前主要设置 `PREPARATION`、`DEPENDENCY_PROJECTION`、`DEMAND_TREE` 和 `RECURSIVE_SEARCH`。`RESULT_HANDOFF`、`INVENTORY_BINDING` 和 `SMITHING_STATE_BINDING` 尚未完整接入；`AsyncMaxCraftablePlanningService` 和 `AsyncPlanResponseService` 也没有使用 `PlanningSession`。因此不能只做翻译映射，需要先统一会话或引入位于这些服务之上的请求级状态对象。

### 2.3 规划入口

JEI 发起请求时，客户端目前只显示一次 actionbar 消息，然后向服务端发送 `GenericCraftPacket`。EMI 和未接线的 `PlanPreviewClient` 也能发起预览：

- [RecipeGuiLayoutsMixin.java](../src/main/java/com/huanghuang/rsintegration/mixin/jei/RecipeGuiLayoutsMixin.java)
- [EmiCraftButtonResolver.java](../src/main/java/com/huanghuang/rsintegration/compat/emi/EmiCraftButtonResolver.java)
- [PlanPreviewClient.java](../src/main/java/com/huanghuang/rsintegration/crafting/plan/PlanPreviewClient.java)

服务端在 `GenericCraftPacket` 中捕获 `PlanningSnapshot`，满足条件时调用 `PlanRequestService.submitRouted(...)`，最终发送一个完整的 `PlanResponsePacket`：

- [GenericCraftPacket.java](../src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java)
- [PlanResponsePacket.java](../src/main/java/com/huanghuang/rsintegration/crafting/plan/PlanResponsePacket.java)

当前没有中间的规划进度网络包。

当前关联 ID 并不统一：`CraftingPlanScreen` 内的刷新请求会生成非零 `requestId`，但 JEI、EMI 和 `PlanPreviewClient` 的首次预览使用旧构造器，发送的是 `requestId = 0`。服务端另有每玩家递增的 `previewGeneration`，但客户端发起请求时并不知道它。实现进度卡片前必须先解决这一关联问题。

### 2.4 完整请求路径

当前一次预览可能经过：

```text
客户端点击
  -> PreviewRateLimiter
  -> RecipeIndex 未就绪时进入 WARM_UP_REQUESTS
  -> 服务器线程 tryBuildPlan / PlanningSnapshot 捕获
  -> 纯路径：AsyncPlanningCoordinator worker
  -> 回到服务器线程继续 tryBuildPlan
  -> typed 路径：TYPED_PREVIEW_REQUESTS 排队并在 server tick 执行
  -> AsyncPlanResponseService worker 封装 PlanResponse
  -> 回到服务器线程校验并发送 PlanResponsePacket
```

直接材料计划、缓存命中和早期校验失败会跳过其中若干阶段。最大可合成数量还会进入 `AsyncMaxCraftablePlanningService` 或多轮服务器线程探测。

### 2.5 现有执行进度 HUD

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

`PlanningSession.phase` 是 `volatile`，但 `PlanningSession` 只存在于部分纯规划方法内部，没有注册到可查询的服务中。`AsyncPlanningCoordinator` 的活动请求表也是私有的，没有进度读取接口。预热队列和 typed preview 队列位于 `GenericCraftPacket`，也不属于该 session。

需要新增一个不可变快照，例如：

```java
PlanningProgressSnapshot {
    requestId;        // 必须统一为非零客户端关联 ID
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

建议由请求级 `PlanningProgressHandle` 持有 `AtomicReference<PlanningProgressSnapshot>`。handle 必须在 `GenericCraftPacket.handle()` 接受请求时创建，并跨预热、纯规划、typed resolver 和响应封装存活，而不是在 `PlanningSession` 构造时才创建。

服务器线程可以在现有 `GenericCraftPacket.tickWarmUpRequests()` tick 入口中读取 dirty snapshot 并发送；后台线程只更新原子快照，不直接访问网络对象。

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

取消还必须同时清理 `WARM_UP_REQUESTS`、`TYPED_PREVIEW_REQUESTS`、planner worker 和尚未发送的 response finalization。只调用 `AsyncPlanningCoordinator.cancel(UUID)` 不能覆盖完整生命周期。

### 3.4 多个提前返回没有统一终态

当前部分失败只发送聊天/actionbar 消息，或直接丢弃旧请求，例如：

- `PreviewRateLimiter` 拒绝。
- 配方目录构建失败或预热队列已满。
- typed preview 队列满或排队超时。
- 新 generation 替换旧 generation。
- 玩家离线、服务停止或规划配置重载。
- FTB Quests 合成目标等专用入口绕过普通配方规划。

一旦客户端先创建规划卡片，这些路径必须发送可关联的失败/取消终态，或由新的统一请求服务负责清理，否则卡片会永久停留。

## 4. 推荐的数据模型

### 4.1 状态

建议将规划状态定义为：

```text
ACCEPTED     服务端已接受请求
WARMING_UP   等待配方目录完成
QUEUED       等待 planner worker 或 typed preview 准入
RUNNING      正在执行某个阶段
FINALIZING   正在封装、校验并发送计划响应
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
| 等待配方目录 | `WARM_UP_REQUESTS` | RecipeIndex 尚未可用 |
| 收集目标配方 | `PREPARATION` | 从不可变快照准备目标和库存视图 |
| 裁剪无关依赖 | `DEPENDENCY_PROJECTION` | 构造目标依赖子图 |
| 分析中间节点 | `DEMAND_TREE` | 检查递归需求和可达性 |
| 检查库存与 NBT | `INVENTORY_BINDING`、搜索期间的匹配 | 需要补充更细的埋点，不能只依赖当前枚举 |
| 等待兼容规划 | `TYPED_PREVIEW_REQUESTS` | 等待服务器线程 typed resolver 配额 |
| 检查特殊配方 | typed resolver | 服务器线程的有界兼容规划 |
| 生成合成计划 | `RECURSIVE_SEARCH`、`RESULT_HANDOFF` | 完成搜索并转换为最终计划 |
| 封装计划结果 | `AsyncPlanResponseService` | 后台构造 `PlanResponse` 并回到服务器线程校验 |

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

当前批量合成包 ID 分组中 `9` 仍空闲，可以用于一个 S2C 规划进度包。若增加 C2S 取消包，应使用新的固定 ID，并递增当前统一网络协议版本；不能复用已退役的 ID。

### 6.2 统一关联 ID

推荐把客户端关联 ID 提取为一个会话级生成器，所有预览入口都必须携带非零 ID：

- JEI 首次预览。
- EMI 首次预览。
- `CraftingPlanScreen` 刷新和最大数量请求。
- 后续启用的 `PlanPreviewClient`。

`previewGeneration` 继续作为服务端权威的新旧请求判断，`requestId` 用于把网络更新关联到客户端卡片。二者都应进入快照和终态包。

备选方案是服务端收到旧式 `requestId = 0` 后先返回一个 start/ack 包，但这会增加一次握手并使客户端的本地等待卡片难以关联，不如统一入口直接生成 ID。

### 6.3 发送频率

后台线程只更新原子快照。网络发送可复用现有 `GenericCraftPacket.tickWarmUpRequests()` 的服务器 tick 入口，每 2～5 tick 检查 dirty 状态，即约 100～250ms。

不要让 planner worker 直接操作 Minecraft 网络或 GUI 对象。

建议：

- 状态变化立即发送一次。
- 普通运行状态按 100～250ms 节流。
- 完成、失败、取消、超时各发送一次终态。
- 新请求替代旧请求时发送旧请求终止或让客户端按 generation 丢弃。

### 6.4 客户端 tracker

建议新增独立的 `PlanningProgressTracker`，按非零 `requestId` 保存规划卡片，同时记录服务端 `requestGeneration` 以拒绝旧更新。

它不应写入 `CraftProgressTracker`，原因是：

- 规划请求还没有 `craftId`。
- 规划可能最终失败，不会创建执行任务。
- 同一个玩家只能有当前规划请求，但执行阶段可以同时有多个 craft。
- 两种进度的百分比和终态语义不同。

## 7. 失败、超时和取消

### 7.1 规划完成

规划成功后建议按以下顺序处理：

1. 发送 `FINALIZING` 或“规划完成，正在生成计划”的短暂状态。
2. 服务器线程完成状态重新校验和最终响应生成。
3. 发送最终 `PlanResponsePacket`。
4. 客户端移除规划卡片，或者短暂显示成功过渡状态。

当前流程中的预览完成只会打开或更新 `CraftingPlanScreen`，不会自动启动合成。因此这里不能显示“正在启动合成”。只有玩家在计划界面确认执行后，才进入 `AsyncCraftChain` 的执行进度生命周期。

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
6. `WARM_UP_REQUESTS`、`TYPED_PREVIEW_REQUESTS`、`AsyncMaxCraftablePlanningService` 和 `AsyncPlanResponseService`：接入同一个请求级 progress handle。
7. 所有预览入口：统一生成非零 `requestId`。
8. 网络层：新增规划进度包、包 ID、编解码和客户端 handler，并更新协议版本。
9. 客户端：增加规划 tracker 和 HUD 卡片；可以复用 `CraftProgressOverlay` 的颜色、布局和渲染工具，但不要复用执行快照类型。
10. JEI/EMI 入口：把一次性的后台计算提示替换为启动规划卡片，保留最终计划界面行为。
11. 测试：覆盖快照发布、包编解码、请求替换、取消、超时和所有提前返回终态。

不需要修改：

- 机器并发窗口。
- `AsyncCraftChain` 的节点调度逻辑。
- 材料提取、NBT 匹配和机器执行顺序。
- 已有执行阶段 `CraftProgressSnapshot` 的协议。

自上次审计后，`AsyncCraftChain`、批处理 delegate、输入缓冲和并发窗口有较大重构。这些改动位于计划执行阶段，没有改变上述规划请求链，因此“不修改机器并发和执行调度”的结论仍成立。

## 9. 特殊路径和限制

### 9.1 主线程专用配方

`AsyncPurePlanningService` 对 `mainThreadOnly` 请求会直接回退到服务器线程。这类请求不能声称正在后台规划，应显示单独的“正在检查特殊配方”状态，或者继续使用简化提示。

### 9.2 规划前的服务器线程工作

`GenericCraftPacket` 在提交异步任务之前仍会进行配方解析、库存快照捕获、绑定检查和部分直接规划。这些耗时不属于 planner worker 内部，不能仅靠后台规划快照覆盖。

如果需要完整覆盖用户从点击到最终结果的时间，必须额外定义“请求准备阶段”；否则 HUD 应明确表示它只统计后台 planner 阶段。

### 9.3 共享请求和旧响应

相同请求可能共享一次后台计算，但回调使用最新请求上下文。因此进度状态必须携带 requestId 或 generation，客户端不能只按配方 ID判断是否为当前请求。

### 9.4 typed preview 和最大数量规划

typed preview 通过 `TypedPreviewAdmissionQueue` 排队，随后在 server tick 上运行有界 resolver；它不是后台线程计算。HUD 应显示“等待兼容规划/检查特殊配方”，不能统一宣称“后台计算中”。

最大可合成数量有两条路径：完整不可变图可以使用 `AsyncMaxCraftablePlanningService`，其他情况会进行多轮 `tryBuildPlan` 探测。它需要聚合为同一个用户请求，不能让每次 probe 创建独立卡片或把计数重置为零。

### 9.5 专用合成目标

FTB Quests 等合成目标有专用请求和计划生成路径，普通 `GenericCraftPacket` 可能直接跳过处理。规划 HUD 应明确限定为普通配方预览，或为专用目标提供自己的 progress adapter；不能假设每个 `CraftingPlanScreen` 目标都会经过普通 planner。

## 10. 测试清单

### 单元测试

- 快照发布在多线程下不会读到部分更新。
- 计数单调递增，负数和溢出被限制。
- 阶段切换不会造成非法回退。
- `requestGeneration` 不匹配时丢弃更新。
- 所有普通预览入口都产生非零且会话内不重复的 `requestId`。
- 规划进度包正常往返编解码。
- 超长字符串、超大计数和非法枚举被拒绝。

### 生命周期测试

- 排队请求能显示 `QUEUED`。
- 配方目录预热和 typed preview 准入队列能显示各自状态。
- 新请求替代旧请求后，旧请求不能覆盖新请求。
- 取消后一定收到 `CANCELLED` 或可被客户端安全清理的终态。
- 超时后不会生成不完整的执行计划。
- planner 忙、同步回退和主线程专用路径不会遗留进度卡片。
- 限流、预热失败、队列满、玩家离线、配置重载和服务关闭都有明确清理语义。
- 最大数量多轮探测始终归属于一个卡片。

### UI 测试

- 未知总量显示不定进度动画，而不是错误百分比。
- 阶段名称、计数和目标名称在窄窗口不重叠。
- 规划完成后卡片能平滑切换到最终计划界面。
- 失败原因优先显示结构化缺料或绑定原因。
- 断线、切换服务器和客户端重连后不会保留旧请求。

## 11. 推荐实现顺序

1. 先统一 JEI、EMI 和计划界面的非零 `requestId`，并列出所有请求终止出口。
2. 实现跨预热、纯规划、typed resolver 和响应封装的请求级 `PlanningProgressHandle`。
3. 接入阶段状态和低频计数埋点，不做 UI。
4. 增加单元测试，确认计数、取消、超时、提前拒绝和旧请求丢弃语义。
5. 增加 S2C 进度包和客户端 tracker，只显示阶段、计数和动画。
6. 将 JEI/EMI 的一次性提示替换为规划卡片。
7. 最后再评估是否有某些规划路径能提供可靠的总量并启用真实百分比。

## 12. 最终判断

“阶段 + 已处理数量 + 动画进度 + 明确失败/超时状态”在当前代码基础上仍然可行。复审后的主要风险不是搜索计数本身，而是保证一个卡片完整覆盖多段异步/同步路径，并让每个提前返回都产生终态。

“已检查配方数直接作为合成完成百分比”不可行，也不应实现。真实百分比只有在规划器能够证明总任务量稳定时才有意义。

该功能不会自动修复材料依赖聚合、叶子材料收集或中间节点选择问题；它只改善后台规划过程的可见性。
