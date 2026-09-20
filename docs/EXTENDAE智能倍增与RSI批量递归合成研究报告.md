# ExtendedAE Plus 智能倍增与 RSI 批量递归合成研究报告

## 1. 研究目标

本文对比 `ExtendedAE_Plus-develop-1.20.1` 的智能倍增样板机制与当前 RSI 的递归合成实现，回答三个问题：

1. ExtendedAE 的倍增原理是否适合 RSI；
2. RSI 当前已经具备哪些批量合成能力；
3. 如何在不破坏 RS/BD、递归树、材料预留和失败回滚的前提下优化大批量合成。

结论先行：ExtendedAE 的核心思想可以借鉴，但不应直接移植其 AE2 样板包装类。RSI 应增加一个独立的“批量计划决策层”，把连续的同配方执行压缩为受能力约束的批次，再交给现有 `AsyncCraftChain`、材料账本和 delegate 执行。

## 2. ExtendedAE Plus 的智能倍增机制

### 2.1 核心调用链

ExtendedAE 在 AE2 的合成计划构建阶段修改 `CraftingSimulationState.buildCraftingPlan`：

```text
AE2 请求数量
  -> CraftingSimulationStateMixin
  -> 读取每个样板的 totalAmount
  -> 判断样板是否允许缩放
  -> 计算单批倍率和供应器限制
  -> ScaledProcessingPattern(original, multiplier)
  -> AE2 按缩放后的输入/输出执行
```

主要文件：

- `ExtendedAE_Plus-develop-1.20.1/src/main/java/com/extendedae_plus/mixin/ae2/autopattern/CraftingSimulationStateMixin.java`
- `ExtendedAE_Plus-develop-1.20.1/src/main/java/com/extendedae_plus/util/smartDoubling/PatternScaler.java`
- `ExtendedAE_Plus-develop-1.20.1/src/main/java/com/extendedae_plus/api/crafting/ScaledProcessingPattern.java`

### 2.2 倍率拆分

如果请求数量为 275，单批限制为 64，ExtendedAE 会生成：

```text
64 + 64 + 64 + 64 + 19
```

每个批次的输入和输出都按倍率线性放大，但原始样板对象不被修改。不同倍率的包装对象通过 `equals/hashCode` 区分，避免 AE2 计划表中的键冲突。

### 2.3 多供应器轮询

当没有单供应器上限且开启轮询时，ExtendedAE 会统计可用供应器数量，把请求均分到多个供应器。例如 1000 次、4 个供应器会分配为 250/250/250/250。该机制的目标是减少单机瓶颈，而不是改变配方语义。

### 2.4 安全边界

智能倍增只对显式实现 `ISmartDoublingAwarePattern` 且供应器开启 `allowScaling` 的处理样板生效。样板级限制优先于全局限制，供应器更新样板时重新计算输入约束。流体按 1000 单位换算，避免把物品数量和流体数量直接混算。

## 3. RSI 当前实现盘点

### 3.1 递归计划生成

RSI 的递归解析主要由以下模块负责：

- `CraftingResolver`：配方候选、递归展开、循环检测、输入缺口解析；
- `CandidateEngine`：候选配方与库存可用性判断；
- `StepExecutor`：按批次生成步骤、输入需求和输出声明；
- `CraftPlanGraph`：将递归结果表达为 DAG 节点及材料分配；
- `CraftingPlanScreen`：展示计划和缺口。

`StepExecutor.craftBatched(...)` 已经以 `batches` 为参数，按批次乘输入、主输出、副产物和容器余物。`CraftingResolver` 在递归展开时也会根据净产出计算所需批次数，并处理自消耗配方的阶段性拆分。

这说明 RSI 已经具备“批量计划”的数学基础。

### 3.2 AsyncCraftChain 执行能力

`AsyncCraftChain` 已实现：

- 虚拟库存和中间产物流转；
- 材料账本、预留、提交、退款；
- vanilla 步骤的批量执行和每 tick 限额；
- 非 vanilla delegate 的批次启动与失败清理；
- 多机器并行组 `ParallelCraftGroup`；
- 机器租约、操作预算和捕获输出；
- 图执行与平铺执行的自动选择；
- 大节点自动切换为 tick-sliced 执行，避免单次阻塞主线程。

当前关键限制配置包括：

- `craftingOperationsPerDispatch`：单次非 vanilla dispatch 的最大操作数；
- `craftingVanillaOperationsPerTick`：单链每 tick 的 vanilla 执行数；
- `craftingGlobalVanillaOperationsPerTick`：全局 vanilla 执行预算；
- `craftingMaxConcurrentGraphNodes`：并行 DAG 节点数；
- `craftingMaxConcurrentOperations`：单个合成任务的机器操作并发数；
- `craftingOperationDispatchPerCraft`：单次合成任务允许启动的机器操作总数；
- `craftingParallelDisabledMods` 与 delegate policy：并行能力控制。

### 3.3 RS/BD 存储抽象

递归执行依赖 `CraftStorageEndpoint` 和 `StorageSession`，而不是直接绑定 RS 原生网络对象。这样 RS 和 Beyond Dimensions 都可以提供统一的：

- `snapshot`；
- `extractExact`；
- `extractMatching`；
- `insert`。

因此，批量优化应放在计划与执行层，不应重新实现 RS/BD 的库存同步或抽取逻辑。

## 4. 两套机制的本质差异

| 项目 | ExtendedAE Plus | RSI |
|---|---|---|
| 调度对象 | AE2 处理样板 | 异构机器 delegate + vanilla 配方 |
| 缩放位置 | AE2 合成计划内部 | RSI 递归计划和执行链 |
| 输入输出关系 | 样板输入输出可线性放大 | 部分机器有副产物、容器余物、状态和随机性 |
| 失败处理 | AE2 自身计划机制 | RSI 账本、虚拟库存、捕获输出和退款 |
| 并行模型 | 同样板供应器轮询 | 机器能力、租约、捕获范围和冲突策略 |
| 存储后端 | AE2 MEStorage | RS 与 BD 的统一 StorageSession |
| 可直接移植内容 | 批次拆分、上限、轮询、缩放对象思想 | 不应直接移植 AE2 PatternDetails 包装 |

关键结论：RSI 不能对所有配方做无条件的“输入输出乘倍率”。必须由 delegate 声明批量能力，并继续经过材料预留和输出捕获校验。

## 5. 可借鉴的优化方案

### 5.1 增加 BatchSizing 层

建议在计划生成完成、进入 `AsyncCraftChain` dispatch 前增加一个纯计算层，例如：

```text
BatchSizingPlanner
  输入：ResolutionStep、delegate 能力、剩余执行次数、材料账本、机器容量、配置
  输出：BatchSlice 列表
```

单批大小：

```text
batch = min(
    remainingExecutions,
    globalBatchLimit,
    delegateBatchLimit,
    machineCapacity,
    materialReservationCapacity,
    outputCaptureCapacity
)
```

此层只决定“每次尝试多少次”，不直接抽取材料、不操作机器、不修改原始配方。

### 5.2 full + remainder 拆分

例如剩余 275 次，配置和机器共同决定单批最多 64 次，则输出：

```text
64, 64, 64, 64, 19
```

如果材料预留只能支持 70 次，则计划应变为：

```text
32, 32, 6
```

批次完成后再根据虚拟库存和下一层产物重新计算，而不是一次性假设全程成功。

### 5.3 复用现有材料账本

每个 BatchSlice 必须先走现有 ledger reservation：

1. 计算该批次所需输入；
2. 从初始库存或中间产物预留；
3. 预留失败时缩小批次并重试；
4. delegate 启动后，按捕获输出结算已完成操作；
5. 未完成部分通过现有退款/虚拟恢复路径处理。

不能因为批次更大而绕过 `ExtractionLedger`、`CraftStorageReservationView` 或 `MaterialBroker`。

### 5.4 能力声明

建议扩展 `IBatchDelegate` 或增加独立 capability 接口：

```java
interface BatchScalingCapability {
    boolean supportsLinearScaling();
    int maxOperationsPerBatch();
    boolean hasDeterministicOutput();
    boolean supportsParallelDispatch();
}
```

默认策略应为保守：没有声明能力的 delegate 仍按当前单批逻辑运行。

### 5.5 多机器轮询

`ParallelCraftGroup` 已经提供并行 worker、操作 ID、材料预留和完成结算。可以借鉴 ExtendedAE 的 round-robin，但必须保留 RSI 的冲突检查：

- 机器类型和绑定位置必须兼容；
- 输入槽位和输出捕获区域不能冲突；
- delegate 必须允许并行；
- 配置黑名单和 policy 优先级高于自动判断。

轮询只应影响批次到机器的分配，不应改变配方选择和材料优先级。

## 6. 不建议直接照搬的部分

### 6.1 不要把所有配方包装成 ScaledProcessingPattern

ExtendedAE 的 `ScaledProcessingPattern` 适用于 AE2 处理样板。RSI 的 TACZ、Embers、炉子、祭坛和特殊机器可能存在：

- 非线性副产物；
- 容器余物；
- 随机输出；
- 机器状态或能量状态；
- 输入顺序敏感；
- 输出捕获失败后无法安全重放。

这些配方必须继续由对应 delegate 决定批量大小。

### 6.2 不要把库存数量当成批量容量

库存中有 10,000 个材料，不代表机器可以一次处理 10,000 次。批次上限还受机器槽位、输出空间、能源、操作预算和捕获能力约束。

### 6.3 不要一次预留整个递归树

全量预留会增加锁持有时间和失败退款复杂度，也会放大 RS/BD 存储压力。应采用“窗口化预留”：只为当前可执行批次和少量下一批材料预留。

## 7. 推荐实施路线

### 阶段一：只做批次决策，不改变行为

- 增加 capability 接口和默认值；
- 在日志中输出每个 step 的建议 batch；
- 对现有 delegate 做能力盘点；
- 增加纯函数单元测试：上限、余数、材料不足、整数溢出。

### 阶段二：优先优化 vanilla 和已验证 delegate

- vanilla crafting；
- 已有确定性输出捕获的机器；
- 明确支持批处理的 delegate。

通过 `BatchSizingPlanner` 产生 `BatchSlice`，接入现有 `AsyncCraftChain`。

### 阶段三：并行轮询和动态缩小

- 复用 `ParallelCraftGroup`；
- 按机器容量均分批次；
- 预留失败时二分缩小批次；
- 输出捕获不足时停止继续放大；
- 保留现有失败回滚和虚拟库存恢复。

### 阶段四：特殊配方白名单

对 Embers、TACZ、Goety 等配方逐个验证后再开启批量缩放。未验证类型继续走当前保守路径。

## 8. 建议配置

建议新增服务端配置：

```text
enableSmartBatching = true
smartBatchingMaxOperations = 64
smartBatchingMinBenefit = 4
smartBatchingRoundRobin = true
smartBatchingReservationWindow = 2
```

配置语义：

- `enableSmartBatching`：总开关；
- `smartBatchingMaxOperations`：单批全局上限；
- `smartBatchingMinBenefit`：低于该执行次数不做批量重写；
- `smartBatchingRoundRobin`：是否在多个兼容机器间均分；
- `smartBatchingReservationWindow`：最多提前预留多少个批次。

应继续复用现有 `craftingOperationsPerDispatch` 作为硬安全上限，不能让新配置绕过它。

## 9. 测试重点

必须覆盖：

1. `275 -> 64+64+64+64+19`；
2. 材料只够 70 次时的动态缩小；
3. 中间产物不足时的递归批次重新计算；
4. RS 和 BD endpoint 的抽取/插入一致性；
5. 并行机器分配后的总输出不重复、不丢失；
6. delegate 报告部分完成时只结算真实完成量；
7. 失败时未完成预留全部可恢复；
8. TACZ/Embers 等非线性配方默认不启用；
9. 大数量乘法溢出和 `long -> int` 边界；
10. 递归树的缺口显示仍使用“库存量”和“缺口量”两个独立值。

## 10. 两种批处理必须区分

前文的“批量”实际上包含两种不同机制，不能用同一个 `supportsBatching` 标志混合处理。

### 10.1 类型 A：真正的倍率缩放

机器或供应器可以把一次配方执行直接扩大为多份：

```text
原始配方：输入 1，输出 1
倍率 64：输入 64，输出 64
```

它适合具有确定性输入/输出、并且原生允许批量提交的处理样板。ExtendedAE Plus 的 `ScaledProcessingPattern` 属于这一类。

这类优化可能减少实际机器启动次数，但不能用于所有 RSI delegate。随机副产物、状态型机器、输入顺序敏感的机器必须默认关闭。

### 10.2 类型 B：输入缓冲批处理

机器每次仍然只消耗一个物品，但输入槽可以提前放入一整组，机器会自动连续运行：

```text
输入槽：64 个
机器行为：每次消耗 1 个，共处理 64 次
```

熔炉就是典型例子。它不是一次烧制 64 个，而是提前装入 64 个后连续烧制 64 次。多数“允许输入槽堆叠、自动启动、持续处理”的魔法机器也属于这一类。

RSI 对这类机器的优化目标是减少控制轮次：

```text
旧流程：放 1 个 -> 等待/检测 -> 再放 1 个
新流程：一次预留并放入 64 个 -> 机器自动连续处理 -> 统一或分段收集
```

机器实际处理时间基本不变，但可以减少输入槽交互、RS/BD 抽取、状态检查、输出捕获和网络进度更新。

### 10.3 Iron Furnaces 同时包含类型 A 和类型 B

Iron Furnaces 不能按整个模组统一归类，必须在 delegate 完成机器探测后按具体模式决定。对当前使用的 Iron Furnaces 4.1.6 本体字节码核对结果如下：

- 普通炉调用 `smeltItem(...)`，工厂普通炉道调用 `smeltFactoryItem(...)`；每个完成周期只消耗 1 个输入，输入槽虽然能堆叠，但属于类型 B；
- 彩虹炉调用 `smeltItemMult(...)`，彩虹工厂炉道调用 `smeltFactoryItemMult(...)`；一个完成周期会按输入数量和输出槽剩余空间同时处理多份，属于类型 A；
- 工厂强化提供 6 条可并行炉道，它与“单周期处理几份”是另一个独立维度。

因此实际存在四种组合：

| 模式 | 并行炉道 | 单炉道预装能力 | 每炉道每周期处理 | 正确优化方式 |
|---|---:|---:|---:|---|
| 普通 Iron 炉 | 1 | 最多 64 | 1 | 类型 B：一次预装，连续等待多个周期 |
| 普通 Iron 工厂 | 最多 6 | 每道最多 64 | 每道 1 | 6 路并行 + 每路类型 B 缓冲 |
| 彩虹 Iron 炉 | 1 | 最多 64 | 本周期最多整组 | 类型 A：单周期倍率处理 |
| 彩虹 Iron 工厂 | 最多 6 | 每道最多 64 | 每道本周期最多整组 | 6 路并行 + 每路类型 A，理论物理窗口最高 384 |

现有 `IronFurnacesBatchDelegate` 已通过 `isRainbowFurnace()` 区分单周期宽度：彩虹单炉道按安全容量最多 64，非彩虹单炉道按 1；工厂再乘最多 6 条炉道。因此现有执行的产量计算没有把普通炉误当成彩虹炉。尚未完成的优化是：普通炉和普通工厂虽然可以预装堆叠输入，当前仍以一次一个的方式循环放料，尚未利用类型 B 的“预装 64、机器自行连续消费”。

这里需要把三个量彻底分开：

```text
bufferCapacity          // 每条炉道能预装多少个逻辑操作
operationsPerCycle      // 一个机器完成周期实际处理多少个逻辑操作
parallelLaneCount       // 同时运行多少条等价炉道
```

`OutputContract.perOperation` 始终表示一个配方逻辑操作的产量，不能改成“每机器周期产量”。结算期望值仍为 `logicalOperations * perOperation`；周期宽度只影响预计完成时间、燃料周期数、轮询和下一批启动时机。这样普通炉预装 64 后仍预期 64 份产物，彩虹炉一次处理 64 后也预期 64 份产物，但两者的完成判定不会混用。

### 10.4 两类能力接口

建议将 delegate 能力拆开：

```text
supportsScaledProcessing  // 类型 A：一次提交多份处理
supportsInputBuffering    // 类型 B：输入槽可预装并自动连续运行
maxScaledMultiplier       // 类型 A 的倍率上限
maxInputBuffer            // 类型 B 的输入缓冲上限
operationsPerCycle        // 每个完成周期处理的逻辑操作数，普通炉为 1
parallelLaneCount         // 等价并行炉道数；不能与配方的多输入槽混用
autoStart                 // 放入输入后是否自动启动
deterministicOutput       // 输出和副产物是否可预测
```

类型 B 的单批大小应取：

```text
min(
    输入槽剩余容量,
    输出槽安全容量,
    可用燃料或能源支持数量,
    剩余需求,
    delegate 上限,
    RSI 全局批次上限
)
```

例如熔炉输入槽能放 64 个，但燃料只够烧 32 个、输出槽只能容纳 40 个，则本批只能提交 32 个。

### 10.5 对 RSI 的实际意义

如果当前大多数魔法机器都是类型 B，那么项目最先获得收益的方向不是移植 ExtendedAE 的“倍率样板”，而是实现通用的“输入缓冲批处理”：

1. 一次为当前批次预留材料；
2. 尽可能一次性填充机器输入槽；
3. 机器自动消耗时不重复发送填料操作；
4. 监视输入剩余量、输出空间和机器状态；
5. 输出达到安全阈值或输入耗尽后结算；
6. 再提交下一批，并保留失败退款和虚拟库存恢复。

这种机制不会把 64 次处理变成 1 次，也不会改变机器处理速度；它把 64 次外围调度压缩为 1 个受控批次。对于处理时间短、网络交互频繁的魔法机器，收益会很明显；对于处理时间很长的机器，主要收益是降低服务器调度和存储操作压力。

## 11. 最终结论

ExtendedAE Plus 的智能倍增值得借鉴的不是“修改样板”，而是以下调度原则：

```text
按请求量计算批次
  -> 受上限约束拆分
  -> 按机器能力和材料预留动态缩小
  -> 多机器轮询
  -> 每批独立结算和失败恢复
```

RSI 当前已经有递归 DAG、批量步骤、材料账本、虚拟库存、并行组和 RS/BD 存储抽象，因此不需要重写基础架构。最佳方案是在 `AsyncCraftChain` dispatch 前增加统一的批次大小决策层，并让每个 delegate 分别声明是否支持“真正倍率缩放”和“输入缓冲批处理”。

对你当前的机器生态，建议优先实现类型 B，再对少量明确支持类型 A 的机器开启倍率缩放。这样可以减少重复计划、材料预留、槽位交互和机器启动次数，同时保留 RSI 对异构配方、特殊输出、失败回滚以及 RS/BD 双后端的兼容性。

## 12. 当前拆分进度与多槽位设计

本轮已经开始把原来集中在 `AsyncCraftChain` 的职责拆开，当前状态如下：

| 模块 | 当前职责 | 状态 |
|---|---|---|
| `BatchDispatchPlanner` | 平铺窗口、Vanilla 切片、调度上限 | 已完成 |
| `MachineDispatchPlanner` | 机器去重、租约过滤、候选筛选 | 已完成 |
| `MaterialReservationPlanner` | 批次缩小、材料规格缩放、复用材料索引 | 已完成 |
| `FlatCraftExecutor` | Vanilla/平铺配方执行、中间产物和容器余物 | 已完成 |
| `InputBufferPlan` | 输入槽位与输出端口的物理布局 | 已完成 |
| `InputBufferPlanner` | 输入缓冲批次和多槽位容量计算 | 已完成，暂未接入机器 |

### 12.1 多输入、多输出不能继续使用单列表

旧接口的 `List<ItemStack>` 只表达材料顺序，无法表达以下差异：

- 输入必须放到哪个物理槽位；
- 哪个材料是每次消耗，哪个材料是可复用催化剂或容器；
- 多个输入槽容量不同，批次应按哪个容量裁剪；
- 多个输出槽分别对应哪些产物；
- 输出端口是否需要分别核对数量和失败回收。

因此新协议使用：

```text
InputSlot(slot, stack, reusable)
OutputPort(port, expected)
```

还必须区分两种看起来都像“多个输入槽”的拓扑：

- 配方多输入槽是 `AND` 关系：一次操作必须同时从各槽消耗对应材料，安全批次取所有槽容量的最小值；
- Iron 工厂的多炉道是 `OR/parallel` 关系：每条炉道都是同一配方的独立 worker，材料应在炉道间分配，而不是要求每次操作同时占用六条炉道。

所以 `InputSlot` 只负责描述一条炉道内部的配方输入，不能用六个普通 `InputSlot` 假装六条工厂炉道。后续统一合同需要在输入布局外增加 lane/worker topology；否则规划器会把 6 路并行错误裁成“六种材料必须同时存在”。

`InputBufferPlanner` 会对所有输入槽取安全最小批次。例如：

```text
请求 64 次
输入槽 0：容量 64，每次消耗 1
输入槽 1：容量 8，每次消耗 1
最终批次：8 次
```

可复用输入只放置一次，不会按执行次数错误放大；输出端口则按实际批次生成预期数量。旧 delegate 默认 `supportsInputBuffer() == false`，继续使用原来的逐个放置路径。

### 12.2 当前没有自动改造机器

目前只是加入能力契约和纯规划器，还没有把熔炉、Aether 熔炉或魔法机器切换到缓冲路径。这样可以避免把“支持堆叠输入”错误地等同于“支持自动连续处理”。具体机器必须明确验证：

1. 输入槽是否真的允许预装多个物品；
2. 放入后是否会自动连续消耗；
3. 输出槽是否有足够容量或可安全分段收集；
4. 燃料/能源是否覆盖完整批次；
5. 取消、断线和服务器停止时能否只回收 RSI 自己放入的物品。

只有通过这些条件的 delegate 才实现 `supportsInputBuffer()`、`inputBufferPlan(...)` 和 `tryStartWithInputBuffer(...)`。

## 13. `AsyncCraftChain` 剩余拆分建议

当前 `AsyncCraftChain` 已从约 5200 行降到约 4900 行。剩余代码不应继续按“每个方法一个类”机械拆分，而应按状态边界拆分：

### 13.1 下一优先级：`GraphCraftExecutor`

建议抽取以下职责：

- `tickGraph`；
- Vanilla DAG 节点结算；
- 图节点准备、重试和 dispatch；
- 图材料 checkout 与输出发布。

该模块预计可移出约 1000～1500 行，是当前最大的独立职责。它需要通过上下文访问 `nodeRuntimes`、`graphMaterials`、`graphAdmissions` 和生命周期回调，但不应直接拥有整个 chain 的终止权。

### 13.2 第二优先级：`OutputSettlementCoordinator`

建议集中处理：

- 输出捕获和捕获句柄；
- 物理输出与声明输出匹配；
- 中间产物发布；
- 最终输出入库、玩家背包和掉落回退；
- 输出不足时的恢复判断。

这部分同时被平铺、图执行和失败终止使用，适合先定义小接口，再逐步搬迁，不能直接复制成三套逻辑。

### 13.3 第三优先级：`TerminationCoordinator` 的调用适配

终止代码目前仍和 chain 状态、账本、图运行时、机器清理交织。已有 `TerminationCoordinator` 和 `TerminationService`，后续应把 `AsyncCraftChain.terminate(...)` 缩成协调入口，把“决定退款策略”和“执行资源清理”分别交给现有服务。

### 13.4 暂时不要拆的部分

- `resolvePlayer`、配置读取等小工具：拆出后只增加跳转成本；
- 机器绑定筛选：已经由 `MachineDispatchPlanner` 承担；
- 纯批次计算：已经由 `BatchDispatchPlanner` 和 `MaterialReservationPlanner` 承担；
- 输入缓冲实际接入：应在 `GraphCraftExecutor`/`OutputSettlementCoordinator` 边界稳定后进行。

## 14. 后续实施顺序

```text
当前：规划器与平铺执行器拆分
  -> GraphCraftExecutor
  -> OutputSettlementCoordinator
  -> 终止/退款调用适配
  -> 原版熔炉输入缓冲适配
  -> Aether/魔法机器逐个白名单接入
```

每一步都保持旧 delegate 默认行为不变，并以完整测试通过作为进入下一步的条件。

本节是第一轮路线；结合第二轮拆分审计后的细化顺序，以第 16 节为准。

## 15. 第二轮拆分审计：还有哪些部分值得拆

本轮没有接入任何具体机器，只检查现有代码的职责边界。结论是：还可以拆，但应当围绕“状态所有权”和“失败恢复边界”拆，不能继续按方法数量机械拆类。

### 15.1 `GenericCraftPacket` 是下一块明显的组合职责

`GenericCraftPacket` 当前约 6114 行，已经不只是网络包。它同时包含：

| 职责 | 现状 | 推荐拆分目标 |
|---|---|---|
| 请求协议 | 构造器、`encode/decode`、兼容字段、边界校验 | `GenericCraftRequestCodec`，只负责线协议与请求对象构造 |
| 请求入口与排队 | `handle`、预热队列、执行队列、限流和异常转译 | `CraftRequestDispatcher`，只负责生命周期和服务器线程切换 |
| 计划缓存校验 | cache key、快照重验证、执行前复用 | `CraftPlanAdmission`，只负责“缓存计划能否执行” |
| 递归图过滤 | 绑定机器过滤、运行时可用性、阻塞输出诊断 | `RecipeGraphAvailability`，只接收图和谓词并返回过滤结果 |
| 计划构建 | `tryBuildPlan` 及动态配方、显示材料、输出选择 | `CraftPlanBuilder`，按配方类型委托给已有 handler |
| 执行路由 | 同步执行、异步 chain、准备图、纯计划快速路径 | `CraftExecutionRouter`，只选择执行路径，不构造计划 |
| 纯规划辅助 | Ingredient 缩放、匹配、市场/缺口判断、失败摘要 | `PurePlanningSupport`，保持纯函数优先 |

建议顺序是：

```text
GenericCraftRequestCodec
  -> CraftRequestDispatcher
  -> CraftPlanAdmission
  -> RecipeGraphAvailability
  -> CraftExecutionRouter
  -> CraftPlanBuilder
  -> PurePlanningSupport
```

第一步只移动 `encode/decode` 和不可变请求字段，不改变网络字节布局；第二步只移动队列和线程切换。这样前两步风险最低，也能立即降低主类的协议噪声。`CraftPlanBuilder` 和 `CraftExecutionRouter` 不能同时移动，因为二者目前通过多个局部变量和回调共享上下文。

预计拆分收益：

- 第一阶段可移出约 250～400 行，主要降低协议改动风险；
- 第二阶段可移出约 300～500 行，隔离预热/限流/异常处理；
- 图过滤、缓存准入和执行路由稳定后，再移出约 1000～1800 行计划流程；
- 不追求把文件压到很小，目标是让每个模块只拥有一种失败语义。

### 15.2 `GraphCraftExecutor` 的边界已足够稳定，可以作为下一轮实施对象

`AsyncCraftChain` 中从 `tickGraph` 到节点 dispatch、材料 checkout、增量输出发布的代码共享以下明确状态：

- `nodeRuntimes`：运行中的图节点；
- `graphMaterials`：图级材料 broker；
- `graphAdmissions`：节点材料准入和结算；
- `graphScheduler`：可运行节点调度；
- 三个生命周期回调：节点完成、重试、失败。

因此可以定义一个窄上下文，而不是把整个 `AsyncCraftChain` 传进去：

```text
GraphExecutionContext
  - read-only graph / virtual inventory view
  - node runtime registry
  - material checkout / publish callbacks
  - dispatch budget
  - complete / retry / fail callbacks
```

`GraphCraftExecutor` 第一版只接管：

1. `tickGraph` 主循环；
2. Vanilla DAG 节点结算；
3. 节点准备、重试、dispatch；
4. 图材料 checkout；
5. 声明输出和实际输出的发布回调。

它不应接管：终止退款、玩家背包发放、网络进度包和最终输出目的地。这些仍属于 chain 生命周期或后续 `OutputSettlementCoordinator`。

进入实施前的硬条件：

- 图节点的完成/重试/失败回调能用接口表达；
- 节点 dispatch 不再直接修改 chain 的终止状态；
- 图输出发布可通过一个可测试的 callback 完成；
- 现有图执行测试可以在不启动具体机器的情况下覆盖成功、重试、失败退款三条路径。

### 15.3 `OutputSettlementCoordinator` 仍应晚于图执行器

输出结算同时被平铺执行、图执行和终止恢复使用，过早抽取会复制三套“捕获输出匹配”逻辑。正确顺序是先让 `GraphCraftExecutor` 暴露统一的输出事件，再抽取：

```text
CapturedOutput -> DeclaredOutputMatch -> IntermediatePublish
               -> FinalDestination / PlayerRecovery
```

该模块的验收重点不是行数，而是保证以下不变量：

- 捕获到的实际输出不能被重复结算；
- 中间产物只能进入图级虚拟库存，不能提前写入最终网络；
- 最终输出入库失败时，必须能回退到玩家背包或掉落；
- 取消和断线时只能回收 RSI 自己捕获或预留的物品。

### 15.4 其他大文件的处理优先级

| 文件 | 约行数 | 判断 | 当前动作 |
|---|---:|---|---|
| `ExtractionLedger` | 2204 | 预留、提交、退款、结算镜像共享同一状态机，边界内聚 | 暂不拆，先补状态机测试 |
| `CraftingPlanScreen` | 3353 | 卡片视图、树视图、交互和布局耦合，属于独立 UI 专项 | 不与后端拆分混做 |
| `RSGridSearchCache` | 2221 | 搜索索引、异步 worker、磁盘缓存和 JEI 预热是另一套客户端系统 | 单独做性能专项 |
| `GoetyBatchDelegate` / `WRBatchDelegate` | 约 2.4k | 机器特例较多，暂不适合抽象成通用基类 | 只在出现重复协议时提取小接口 |

判断标准：文件“大”本身不是拆分理由；只有当一个类同时拥有不同生命周期、不同失败恢复策略或不同线程模型时，才进入拆分队列。

## 16. 更新后的实施路线

```text
已完成：批次规划器 / 材料预留规划器 / 平铺执行器 / 输入缓冲规划器
  -> 先统一 delegate 合同并清理无调用兼容入口
  -> GraphCraftExecutor（在合同稳定后再迁移图执行）
  -> OutputSettlementCoordinator（统一输出事件和恢复）
  -> AsyncCraftChain 终止入口瘦身
  -> GenericCraftRequestCodec + CraftRequestDispatcher
  -> GenericCraftPacket 的计划准入 / 图过滤 / 执行路由拆分
  -> 原版熔炉输入缓冲适配
  -> 经过验证的 Aether/魔法机器白名单接入
```

每个箭头都必须满足：编译通过、定向测试通过、完整测试通过，并且旧 delegate 在未声明能力时行为完全不变。当前阶段只更新设计和边界，不提前修改具体机器实现。

## 17. 横向统一性审计：真正混乱的部分

本节不是继续列“大文件拆分”，而是检查同一个概念是否存在多套入口、多个命名或不同失败语义。当前最需要统一的是以下六组协议。

### 17.1 批量能力被拆成了过多的独立开关

`IBatchDelegate` 现在同时暴露：

```text
prepareFlatBatch
flatBatchOperationLimit
expandsFlatBatchOperationLimit
prepareGraphBatch
prepareOperationCount
preferredParallelBatchSize
supportsInputBuffer / inputBufferPlan / tryStartWithInputBuffer
```

这些方法分别由平铺执行、图执行、并行组和输入缓冲路径读取。它们并非完全重复，但都在回答“这个 delegate 一次能安全处理多少工作、以什么物理方式处理”。目前的问题是：

- 能力分散在多个布尔值和整数中，组合关系靠调用方记忆；
- 平铺和图执行可能对同一个 delegate 得出不同的批次结论；
- `prepareFlatBatch` 返回的是即时容量，`preferredParallelBatchSize` 返回的是偏好，但接口层没有统一“硬上限/软偏好”的语义；
- 输入缓冲能力与普通批量能力没有明确互斥或叠加规则。

建议统一为只读的 `BatchExecutionProfile`：

```text
BatchExecutionProfile
  - dispatchMode: SINGLE / REPEATED / INPUT_BUFFER / NATIVE_PARALLEL
  - hardOperationLimit
  - preferredOperationBatch
  - inputLayout (optional)
  - preparationScope: FLAT / GRAPH / BOTH
  - supportsPartialBatch
```

旧方法先保留为兼容适配层，由 profile builder 统一读取；新调度器只读取 profile，不再分别判断七个方法。这样不是简单删接口，而是把“能力声明”和“调度决策”分开。

### 17.2 材料描述存在两套以上的列表协议

当前材料相关入口包括：

- `getRequiredMaterials()`；
- `getGraphSpecs()`；
- `getSupplementalSpecs()`；
- `mergeSupplementalMaterials()`；
- `getMaterialReservationScopes()`；
- `InputBufferPlan.InputSlot`。

这组接口最大的问题不是数量，而是大量依赖“列表下标相同”。一旦 delegate 对 supplemental 材料重新排序、多输入槽位映射或催化剂复用，`specs`、`scopes`、`materials` 和物理槽位可能悄悄错位。

建议统一为 `MaterialPlan`：

```text
MaterialPlan
  - entries: MaterialEntry(id, ingredient, count, role, reservationScope)
  - placement: entry -> input slot / port
  - graphEntries
  - supplementalEntries
  - reusableEntries
```

`mergeSupplementalMaterials()` 应改成按 `MaterialEntry.id` 合并，而不是按列表顺序拼接。`InputBufferPlan` 也应引用 entry id，避免“规划顺序”和“机器槽位顺序”混用。这个统一点对多输入槽、多输出槽和递归中间产物最重要。

### 17.3 输出声明、输出捕获和输出收集是三个概念，但命名容易混淆

现在同时存在：

- `getExpectedOutput()`：主要用于世界掉落捕获；
- `getExpectedProduction()`：用于外部提取/产出数量审计；
- `collectResult()` / `collectAllResults()`：从机器槽位收集实际物品；
- `publishesDeclaredGraphOutputs()`：决定图输出是否进入 broker；
- `collectsPhysicalSecondaryOutputs()`：声明是否已包含副产物。

这些不能全部合并成一个“expected output”，因为世界掉落、机器槽位和图声明的可信来源不同。但应统一成一个 `OutputContract`，内部明确三个通道：

```text
OutputContract
  - declaredOutputs: 图/配方声明
  - observableOutputs: 槽位或实体可观察产物
  - capturePolicy: NONE / WORLD / SLOT / BOTH
  - secondaryPolicy
```

当前 `AsyncCraftChain` 和 `ParallelCraftGroup` 都有自己的捕获快照、数量匹配、drain/close 流程；这部分应统一走已有的 `OperationResourceCoordinator` + `CaptureSession`，并把“数量是否满足”集中到一个 `OutputAccounting` 工具中。否则同一物品可能在一处用 `ItemStack.isSameItem`，另一处用 `MaterialMatcher.sameRuntimeFragment`，出现一处判定完成、另一处判定缺产出的情况。

### 17.4 物品匹配规则已经有中心类，但调用方仍在绕过它

项目已有 `IngredientMatcher` 和 `MaterialMatcher`，并且它们已经处理了 NBT、药水纯度、法术卷轴、SlashBlade 等特殊语义。但执行代码中仍直接使用：

```text
ItemStack.isSameItem(...)
ItemStack.isSameItemSameTags(...)
MaterialMatcher.sameRuntimeFragment(...)
```

这些调用各自有合理场景，但现在缺少显式的匹配策略类型。建议增加 `MatchPolicy` 或等价枚举，至少区分：

```text
INGREDIENT_INPUT       配方输入是否可用
DECLARED_OUTPUT        配方声明是否接受实际产物
RUNTIME_FRAGMENT       中间产物是否可合并/扣除
WORLD_CAPTURE          世界掉落是否属于本次操作
INVENTORY_MERGE        两个物理栈是否可合并
```

编排层只调用策略入口；delegate 特例仍可在 `IngredientMatcher`/`MaterialMatcher` 内部实现。这样能避免继续在新机器接入时随手选择错误的 `isSameItem*`。

### 17.5 RS/BD 存储选择已集中，但调用方仍重复拼装上下文

`CraftStorageEndpoints`、`CraftStorageEndpoint` 和 legacy `INetwork` 适配已经存在，这是正确方向；但 `GenericCraftPacket` 仍在多个分支重复处理：

- `StorageReference` 解析；
- 默认端点回退；
- legacy `INetwork` 转 endpoint；
- 计划阶段和执行阶段分别获取可用库存；
- 输出目的地为玩家背包时的剩余物处理。

建议增加不可变的 `CraftStorageContext`：

```text
CraftStorageContext
  - endpoint (RS / BD 统一抽象)
  - legacyNetwork (仅兼容旧路径)
  - dimension / machine position
  - output destination
  - snapshot / reservation policy
```

计划、执行、递归中间材料和最终输出都传这个 context，不再同时传 `INetwork`、`CraftStorageEndpoint`、维度和位置四组可空参数。这样可以减少“预览选了 BD，执行分支又回退到 RS”的上下文漂移。

### 17.6 生命周期状态有多套枚举，不能直接合并但需要映射

`PreparationState`、`CraftPhase`、`OperationExecutionKernel.TerminalClass` 和并行组的 draining 状态处于不同层级，不能粗暴合成一个枚举。不过当前调用方需要自行推断它们之间的关系，容易造成失败退款判断不一致。

建议保留各层枚举，同时增加一个统一映射结果：

```text
OperationStatus
  - preparation: READY / RETRY / FATAL
  - execution: NOT_STARTED / IN_FLIGHT / SETTLED
  - observation: WAITING / WORKING / DONE / FAILED
  - recovery: NOT_REQUIRED / REQUIRED / COMPLETED / UNKNOWN
```

退款策略只读取 `OperationStatus`，不再在 `AsyncCraftChain`、`CraftNodeRuntime`、`ParallelCraftGroup` 中重复拼接条件。

## 18. 哪些部分不应强行统一

以下差异是业务真实差异，不应为了减少类或方法而抹平：

- RS 和 BD 的底层存储操作可以统一为 endpoint，但不能假设两者的快照、插入失败和会话生命周期完全相同；
- 世界掉落捕获和机器槽位收集必须保留不同的可信度与回收策略；
- Vanilla 平铺配方、物理机器配方和动态 NBT 配方不能共用一个“结果等于配方输出”的简单模型；
- `PreparationState` 与 `CraftPhase` 属于不同时间轴，应该做映射而不是直接替换；
- 机器 delegate 的特殊材料顺序、催化剂复用和多端口输出应由 `MaterialPlan` 表达，不能强迫所有机器退回单一列表。

## 19. 统一工作的优先级

不接入具体机器时，建议按以下顺序做协议统一：

```text
1. MatchPolicy / OutputAccounting
   先消除“同一产物不同判定”的风险
2. BatchExecutionProfile
   统一平铺、图、并行和输入缓冲的能力声明
3. MaterialPlan
   消除材料列表下标和 supplemental 拼接风险
4. CraftStorageContext / OutputSink
   统一 RS/BD、计划/执行和最终输出回退
5. OperationStatus
   最后统一失败、退款和恢复判断
6. 再按第 16 节拆 GraphCraftExecutor 和 GenericCraftPacket
```

这些工作都可以先以纯模型、适配器和测试完成，不需要马上修改熔炉或魔法机器。统一完成后再接入具体机器，新增 delegate 只需填写能力、材料和输出协议，避免继续增加平行布尔值和特例分支。

## 20. 修正后的拆分结论与旧接口清理清单

### 20.1 对之前“下一步直接拆 `GraphCraftExecutor`”的修正

之前把 `GraphCraftExecutor` 判定为下一步，是按代码区域边界判断的；本次检查调用关系后，需要把结论改成：

```text
先统一 delegate 合同
  -> 删除无调用的兼容入口
  -> 迁移仍在使用的旧入口
  -> 再拆 GraphCraftExecutor
```

原因是图执行器、平铺执行器和并行组现在仍共享多组旧接口。如果先拆，旧接口会被复制到新上下文中，最终变成“多个小类共同维护同一套混乱协议”。

### 20.2 入口清理与过渡处理

这些入口经过调用点检查后，有的确实没有生产路径，有的虽然暂未启用但承载后续多槽位/多端口能力，处理方式不能混为一谈：

| 入口 | 现状 | 处理结论 |
|---|---|---|
| `PreparationMessageScope.validate(...)` | 原图节点预检查曾调用它；现已改为读取 `prepare(...) == READY` | 已完成迁移，不要求 delegate 修改 | 直接删除 |
| `supportsInputBuffer()` | 只有接口声明和注释，没有调度器调用方 | 不能直接删除；迁移为稳定的 `InputBufferContract` 能力位 |
| `inputBufferPlan(...)` | 当前没有生产调用方 | 不能丢弃多槽位布局，迁移为带 slot/port 身份的 `InputBufferContract` |
| `tryStartWithInputBuffer(...)` | 当前没有生产调用方，也没有机器实现 | 不能丢弃批量预装入口，迁移为接收结构化槽位计划的 `start(context)` |

这里不能把“当前没有调用方”误解成“能力没有价值”。输入缓冲设计必须保留，因为后续熔炉和大多数机器都可能需要它；真正要移除的是三个零散方法形态，而不是多槽位、多端口和预装批处理语义。迁移完成前，现有三个方法只能标记为过渡 API，不能要求后续重新补回。

### 20.3 必须先让 delegate 修改，再删除的旧入口

#### A. `supportsConcurrentNodeExecution()`

`GraphConcurrencyPolicy` 已明确：只有 `concurrencyCapabilities()` 完整通过时才允许并发，旧布尔值不能启用并发，只能生成拒绝原因。当前仍有以下 delegate 覆盖它：

```text
CrockPotBatchDelegate
LithumAltarBatchDelegate
EnchantalCoolerBatchDelegate
IronFurnacesBatchDelegate
MalumSpiritCrucibleBatchDelegate
VanillaMachineBatchDelegate
CookingMachineBatchDelegate
CuisineBoardBatchDelegate
KettleBatchDelegate
MokaPotBatchDelegate
```

迁移要求：

1. 每个 delegate 把真实的材料所有权、清理、输出所有权和副作用写入 `BatchConcurrencyCapabilities`；
2. 不满足完整合同的 delegate 删除旧布尔值，默认保持串行；
3. `GraphConcurrencyPolicy` 删除读取旧布尔值的诊断分支；
4. 更新相关测试，不再用“旧布尔值为 true”作为能力证明。

完成后可以从 `IBatchDelegate` 和上述 delegate 中删除该方法。

#### B. `isCraftComplete(ServerLevel)`

正式编排路径已经使用 `observeCraft(...)`；`isCraftComplete` 只是旧的二值完成接口。当前仍由 `AbstractBatchDelegate`、`ParallelCraftGroup`、`VanillaMachineBatchDelegate`/`CookingMachineBatchDelegate` 转发，以及若干测试 double 使用。

迁移要求：

- 让所有机器只实现 `observeMachineCraft(...)` 或 `observeCraft(...)`；
- `ParallelCraftGroup` 直接返回聚合后的 `CraftObservation`；
- `CookingMachineBatchDelegate` 不再把完成判断转发成布尔值；
- 测试改为断言 `CraftPhase`。

迁移完成后，删除 `IBatchDelegate.isCraftComplete` 和 `AbstractBatchDelegate` 的旧 final 包装。这个入口不能现在直接删除，因为仍有包装 delegate 和测试实现依赖它。

#### C. `validateAndInit(...)`

正式调度已经通过 `PreparationMessageScope.prepare(...)` 调用带 `READY/RETRY/FATAL` 的协议；`validateAndInit` 仍被接口强制要求，并由大量旧 delegate 实现，导致失败语义被压回一个 boolean。

迁移要求：

- `prepare(...)` 成为唯一准备入口；
- 旧 delegate 把 `validateAndInit` 的实现体迁移为 `prepare`，无法区分永久失败的先暂时返回 `RETRY`；
- 图节点预检查已改用 `PreparationMessageScope.prepare(...)`；旧 `validate(...)` 已删除；
- 所有 wrapper delegate（尤其 `CookingMachineBatchDelegate`、`ParallelCraftGroup`）改为透传 `PreparationResult`。

全部 delegate 迁移后，删除 `validateAndInit` 抽象方法。这个工作量大，但收益明确：不会再把“机器未加载”“没有绑定”“配方不支持”混成同一个 false。

#### D. `tryStartSingleCraft(...)` 与 `tryStartWithMaterials(...)`

这两个入口目前分别代表两种真实物理路径：

- `tryStartSingleCraft`：delegate 自己从存储取材料；
- `tryStartWithMaterials`：chain 已经预留材料，delegate 只负责摆放和启动。

因此不能现在简单删除其中一个。应先增加统一的 `OperationStartContext`，让 delegate 实现一个 `start(context)`，其中明确 `MaterialOwnership = DELEGATE / CHAIN`，并且材料必须带有物理槽位身份，不能继续只依赖 `List<ItemStack>` 的顺序。迁移以下仍有特殊实现的 delegate：

```text
MarketBatchDelegate
MalumSpiritCrucibleBatchDelegate
VanillaMachineBatchDelegate
ParallelCraftGroup
```

其他 delegate 由 `AbstractBatchDelegate` 适配。只有当所有启动路径都通过支持多输入槽位的 `OperationStartContext` 后，才删除两个旧入口及 `tryStartSingleCraft(player, sharedLedger)` 兼容重载。

#### E. `collectResult(...)`

多输出和副产物已经由 `collectAllResults(...)` 部分表达，但它仍然只有无端口的 `List<ItemStack>`，不能作为最终的多输出协议。单输出 `collectResult(...)` 只是旧兼容入口。迁移要求是：

- 先增加带端口身份的 `CollectedOutput(port, stack, kind)` 或等价结构；
- 所有机器把真实收集逻辑迁移到带端口的收集接口；
- `collectAllResults` 只作为无端口旧适配器，不能承担多端口结算；
- `collectResult` 仅由兼容适配器默认返回第一个主输出；
- `AsyncCraftChain`、`ParallelCraftGroup` 和输出结算只调用结构化输出接口。

完成结构化输出迁移后，才能删除 `collectResult` 和无端口的 `collectAllResults` 依赖。不能现在删除，因为多输出机器还需要保留端口归属信息。

### 20.4 暂时保留、但应纳入统一合同的入口

以下方法不是“旧错误接口”，暂时不能移除：

- `getRequiredMaterials()`：仍被大量 delegate 自己调用，先作为 `MaterialPlan` 的兼容投影；迁移时必须保留 entry id、输入槽位、催化剂复用和每次消耗量；
- `getGraphSpecs()` / `getSupplementalSpecs()` / `mergeSupplementalMaterials()`：Embers、Cutting Board、Cooking Pot 等确实使用了图内/图外材料差异，先迁移到带 entry id 的 `MaterialPlan` 再删，不能按列表拼接替代；
- `getExpectedOutput()` 与 `getExpectedProduction()`：分别服务世界捕获和产量审计，统一到 `OutputContract` 后仍需保留两个通道；
- `supportsInputBuffer()` / `inputBufferPlan()` / `tryStartWithInputBuffer()`：迁移到 `InputBufferContract` 前不能删除，否则多输入槽和多输出端口会失去稳定承载位置；
- `onBatchFailed()` / `onBatchFinished()`：仍是 delegate 资源所有权的终止边界，在 `OperationStatus` 和终止协调未完成前不能删除。

## 21. 修正后的实际实施顺序

```text
阶段 0：只做审计模型和兼容适配
  - OperationStartContext
  - BatchExecutionProfile
  - MaterialPlan（entry id、输入 slot、复用范围）
  - InputBufferContract（多输入槽位容量、每次消耗、催化剂复用）
  - OutputContract / OutputAccounting（output port、声明数量、实际数量）

阶段 1：清理已迁移入口
  - 删除 PreparationMessageScope.validate（已完成）
  - 输入缓冲三件套只标记为过渡 API，不删除语义

阶段 2：delegate 批量迁移
  - supportsConcurrentNodeExecution -> concurrencyCapabilities
  - isCraftComplete -> CraftObservation
  - validateAndInit -> PreparationResult

阶段 3：统一启动、收集和材料协议
  - tryStart* -> 支持 slot 身份的 OperationStartContext
  - collectResult -> 带 port 身份的 CollectedOutput
  - getRequired/Graph/Supplemental -> MaterialPlan

阶段 4：此时才拆 GraphCraftExecutor
  - 图循环、节点 dispatch、checkout 使用统一合同

阶段 5：拆 OutputSettlementCoordinator 和 GenericCraftPacket
```

在阶段 2 和阶段 3 完成前，不应该要求具体机器接入输入缓冲，也不应该继续为新 delegate 增加更多 `default boolean`。但多输入槽、多输出端口和输入预装能力必须在新合同中一次性定义清楚；新 delegate 应优先实现新合同，旧方法只由兼容适配器保留，并在迁移表清空后删除。

## 22. 多输入槽、多输出端口的防回退约束

这一节是删除旧接口前的硬性约束，防止后续为了“先清理接口”而丢失机器能力。

### 22.1 输入侧必须保留的字段

`InputBufferContract` / `MaterialPlan` 至少要表达：

```text
InputSlot
  - slotId / physicalSlot
  - entryId
  - ingredient or concrete stack
  - capacity
  - perOperationConsumption
  - reusable / catalyst
  - preloadedCount
  - canShareAcrossWorkers
```

因此以下情况不能再用 `List<ItemStack>` 表达：

- 两个相同物品必须放入不同槽位；
- 一个槽位容量 64，另一个槽位容量 8；
- 催化剂只放一次，但每次循环消耗另一个输入；
- 多个 worker 各自拥有一份复用材料；
- 材料来自图中间产物和 RS/BD 初始库存的混合 checkout。

调度批次必须取所有输入槽的安全最小值，并区分“放置数量”和“每次消耗数量”。不能因为机器允许放 64 个，就把一次消耗 1 个误判成一次执行 64 个。

### 22.2 输出侧必须保留的字段

`OutputContract` / `CollectedOutput` 至少要表达：

```text
OutputPort
  - portId / physicalOutputSlot
  - declaredMaterial
  - expectedPerOperation
  - expectedBatchCount
  - secondary / byproduct
  - source: SLOT / WORLD / VIRTUAL
```

输出结算必须按 `portId` 分开匹配和结算。不能把多个输出槽合并成一个总数后再判断成功，否则会出现：

- 主产物数量足够但副产物缺失仍被误判成功；
- 输出 A 被错误用于填充输出 B；
- 世界掉落和机器槽位的产物被重复结算；
- 多端口失败时无法只回收未完成端口对应的材料。

### 22.3 旧接口删除的前置条件

只有同时满足以下条件，才能删除对应旧方法：

1. 所有 delegate 都能通过 `MaterialPlan` 返回有稳定 id 的输入描述；
2. 所有多输入机器都能通过 slot id 放置材料，而不是按列表顺序猜测；
3. 所有多输出机器都能通过 port id 返回实际产物；
4. `OutputAccounting` 已覆盖主产物、副产物、世界掉落和槽位产物；
5. 失败、取消、断线和服务器停止都能按 entry/port 精确回收；
6. 单槽位旧机器有兼容适配器，行为和现有路径一致。

在这些条件满足前，`tryStartWithMaterials`、`collectResult`、`getRequiredMaterials` 和输入缓冲过渡方法都不能直接删除，只能标记 deprecated 并由适配器转发。

## 23. 接口处理最终决策表

本报告中出现的接口并不是全部都要删除。后续实施以本表为准：

| 接口/能力 | 当前处理 | 是否要求 delegate 立即修改 | 最终去向 |
|---|---|---:|---|
| `PreparationMessageScope.validate(...)` | 清理 | 否，已完成调用方迁移 | 已删除 |
| `supportsInputBuffer()` | 兼容过渡 | 否 | 迁移为 `InputBufferContract` 能力字段后删除旧方法名 |
| `inputBufferPlan(...)` | 兼容过渡 | 否 | 迁移为结构化 slot/port 计划后删除旧方法名 |
| `tryStartWithInputBuffer(...)` | 兼容过渡 | 否 | 合并到带槽位身份的 `OperationStartContext.start(...)` |
| `supportsConcurrentNodeExecution()` | 迁移清理 | 是，涉及旧并发 delegate | 全部改用 `concurrencyCapabilities()` 后删除 |
| `isCraftComplete(...)` | 迁移清理 | 是，涉及 wrapper 和测试实现 | 全部改用 `CraftObservation` 后删除 |
| `validateAndInit(...)` | 迁移清理 | 是，大量旧 delegate | 全部改用 `prepare(...)` 后删除 |
| `tryStartSingleCraft(...)` | 兼容过渡 | 是，按 delegate 分批迁移 | 由 `OperationStartContext` 统一后删除旧入口 |
| `tryStartWithMaterials(...)` | 兼容过渡 | 是，按 delegate 分批迁移 | 由带 slot 身份的启动上下文替代 |
| `tryStartSingleCraft(player, ledger)` | 兼容过渡 | 仅特殊 delegate | 统一启动上下文后删除重载 |
| `collectResult(...)` | 兼容过渡 | 是，多输出 delegate 优先 | 带 `portId` 的结构化收集接口稳定后删除 |
| `collectAllResults(...)` | 兼容适配 | 多输出 delegate 需要迁移 | 无端口旧适配器，不能作为最终多端口协议 |
| `getRequiredMaterials()` | 兼容投影 | 否，先由 `MaterialPlan` 反向提供 | `MaterialPlan` 稳定后删除旧投影 |
| `getGraphSpecs()` | 兼容投影 | 仅 Embers/Cutting Board/Cooking Pot 等 | 由 `MaterialPlan.graphEntries` 替代 |
| `getSupplementalSpecs()` | 兼容投影 | 仅有 supplemental 材料的 delegate | 由 `MaterialPlan.supplementalEntries` 替代 |
| `mergeSupplementalMaterials(...)` | 兼容投影 | 仅特殊材料顺序 delegate | 由 entry id/slot 映射替代 |
| `getExpectedOutput()` | 保留语义、迁移形态 | 世界掉落 delegate 需要 | 永久保留为 `OutputContract` 的 world 通道 |
| `getExpectedProduction()` | 保留语义、迁移形态 | 产量审计 delegate 需要 | 永久保留为 `OutputContract` 的 production 通道 |
| `onBatchFailed()` / `onBatchFinished()` | 保留 | 所有 delegate 都需要 | 终止协调稳定前不删除，最终仍需终止回调 |

### 23.1 读表规则

- 标记为“清理”的，目标是删除旧入口本身；
- 标记为“兼容过渡”的，目标是先让新协议承载同一能力，再删除旧方法名；
- 标记为“保留语义、迁移形态”的，不删除能力，只改变承载结构；
- 多输入槽、多输出端口、催化剂复用、世界掉落捕获和失败回收都属于必须保留的能力，不在清理范围内。

因此，文档中写到“删除”的地方，默认指删除旧接口形态，不指删除对应机器能力。

## 24. 本轮已实施的第一步

本轮先完成低风险合同迁移，随后仅对显式声明输出合同的 Vanilla 机器启用了普通异步链的结构化结算；魔法机器和图执行路径尚未接入：

| 项目 | 实施结果 | 兼容性 |
|---|---|---|
| `PreparationMessageScope.validate(...)` | 已删除 | `AsyncCraftChain` 的预检查改为读取 `PreparationMessageScope.prepare(...) == READY`；旧 delegate 仍通过默认 `prepare` 适配 |
| `InputBufferPlan` | 已扩展 | 输入保留 `entryId / slot / perOperation / reusable`；输出保留 `portId / physicalPort / perOperation / PRIMARY/SECONDARY / SLOT-WORLD-VIRTUAL`；旧构造器仍可用 |
| `InputBufferContract` | 已新增 | 直接复用 `OutputContract.Port`，不再维护第二套输出端口类型；`IBatchDelegate` 默认返回 `none()`，没有 delegate 被自动启用 |
| 输入/输出唯一性 | 已验证 | 输入 entry id、输入 slot、输出 port id、输出 port 都禁止重复 |
| `OutputContract` | 已新增 | 每个输出声明固定 `portId / physicalPort / prototype / perOperation / kind / source`；支持槽位、世界掉落和虚拟输出 |
| `OutputAccounting` | 已新增，并接入普通异步链完成分支 | 按 port、来源、物品声明和批量期望数逐一结算；副产物、短缺和未知端口不会被主产物总数掩盖 |
| `MaterialPlan` | 已扩展 | 统一描述图内/补充材料、稳定 entry id、复用范围和可选输入槽位；新增保留旧摆放顺序的 graph/supplemental 双向兼容投影 |
| `OperationStartContext` | 已新增，尚未接入 | 为未来 `start(context)` 提供 chain 预留材料的结构化交接；拒绝未知或重复 entry id |

本轮新增回归测试覆盖：

- 图节点预检查优先使用结构化 `PreparationResult`；
- 多输入槽位和多输出端口的稳定 id、每次消耗、主/副产物类别以及槽位/世界/虚拟来源不会在批次规划中丢失；
- 主产物和副产物按端口独立结算，短缺、来源不符和未知端口都会拒绝完成；无 NBT 声明仍允许机器为产物附加运行时 NBT；
- 图内和补充材料保持原始物理摆放顺序，旧的 graph/supplemental 列表不再只能依赖简单拼接；
- 旧的无元数据构造路径仍能被现有规划器使用。

### 24.1 已接入图节点的材料兼容层

`AsyncCraftChain` 的图节点预留已开始读取 `MaterialPlan`：

- 普通旧 delegate 的 `getRequiredMaterials()` 自动投影为全部图内材料，行为与旧路径一致；
- 仍在实现旧 `getGraphSpecs()` / `getSupplementalSpecs()` 的 delegate，会按稳定 entry 恢复图内、补充材料以及原始摆放顺序，Embers 的交错方面/输入不再依赖 delegate 自己拼接；
- 复用材料的 worker 容量计算改为从图内 entry 的 `reusable` 读取，补充材料不会错位影响图内索引；
- `ParallelCraftGroup`、CrockPot 的计划 checkout 和私有账本 delegate 暂时保留原分支，避免并行批次材料数或私有提取语义在本阶段变化。

这不是机器接入：最终仍将 `List<ItemStack>` 交给旧的 `tryStartWithMaterials(...)`，所以当前机器摆放、启动、回收的实际行为没有改变。

### 24.2 输出接入的阻塞条件

`OutputContract / OutputAccounting` 已经可用，但不能把旧 `getExpectedProduction()` 自动转换成 `OutputContract.perOperation`。审计发现两种旧语义同时存在：

- 普通 delegate 通常返回单次配方产量；
- Iron Furnaces 等批量 delegate 会把已计划的逻辑操作数算入 `ExpectedProduction.count`，返回的是当前批次总量；无论普通炉需要 64 个串行周期，还是彩虹炉用一个周期处理 64 个，这个旧值都可能同样是 64 份产物，不能从中反推机器周期语义。

如果链路把后一种值再按 `operations` 相乘，就会把期望产量放大一次，造成错误的输出缺失判断。因此不能直接替换所有 `collectAllResults(...)`，而应让需要批量或多输出的 delegate 明确实现 `OutputContract`，声明每次产量和端口来源；没有显式合同的旧 delegate 继续使用旧结算路径。当前 `AsyncCraftChain` 已仅对“显式声明合同”的普通异步链完成分支启用 `OutputAccounting`，但还必须先完成首批机器的游戏内验证，才能扩大迁移范围。

### 24.3 首个显式输出合同

`VanillaMachineBatchDelegate` 已成为第一个实现 `outputContract()` 的 delegate：

| Vanilla 路径 | portId | source | physicalPort | 每次产量 |
|---|---|---|---:|---|
| 熔炉/高炉/烟熏炉 | `vanilla:primary` | `SLOT` | `2` | 配方结果的 count |
| 营火 | `vanilla:primary` | `WORLD` | 无 | 配方结果的 count |
| 石切机/锻造台等虚拟路径 | `vanilla:primary` | `VIRTUAL` | 无 | 配方结果的 count |

Vanilla 已不再走 `collectAllResults(...)`：普通异步链完成时，槽位和虚拟输出由 `collectStructuredResults(...)` 附带唯一端口返回，营火掉落由链路捕获后附带 `WORLD` 端口返回，再交给 `OutputAccounting` 结算。没有 `OutputContract` 的 delegate 完全保持旧收集行为。

Iron Furnaces 仍不能按此方式自动迁移，原因有两层：其旧 `ExpectedProduction.count` 是当前批次总量，不是每次产量；工厂的六个输出槽还是按本批材料分配量分片的等价端口，不能给每个端口都静态声明“全部 operations 的每次产量”。迁移时需要由具体 `InputBufferPlan` 生成本批 `OutputPlan`，记录每条炉道实际承担的逻辑操作数，再逐端口结算。普通单槽和彩虹单槽可以共享“单个逻辑操作的配方产量”声明，但完成周期模型必须分别是串行消费和整批消费。

### 24.4 普通异步链已启用结构化输出结算

本阶段只修改 `AsyncCraftChain` 的普通异步执行 `DONE` 分支，不修改图执行的 `CraftNodeRuntime`：

- 对没有输出合同的 delegate，仍按原顺序合并“捕获的世界掉落 + `collectAllResults(...)`”，没有运行时行为变化；
- 对有合同的 delegate，链路会按 `portId + source + 物品声明` 分端口结算，不再用所有产物的总数掩盖某个副端口缺失；
- 某个端口少产、来源不符或出现未知端口时，已取得的真实产物仍会入库，但本次合成会无退款终止并重置账本，避免“产物已出现且输入又退回”的复制漏洞；
- 世界掉落只有在恰好匹配一个声明为 `WORLD` 的端口时才会归属该端口；匹配零个或多个端口时，会作为未知端口使结算失败，不猜测归属；
- 图执行仍使用既有输出路径，尚未调用 `OutputAccounting`。这点必须在报告和后续代码评审中明确，不能把普通异步链的结果外推到递归图节点。

在继续迁移 `CraftNodeRuntime`、CrockPot 或多输出魔法机器之前，需在游戏内验证以下边界：

| 路径 | 应观察到的结果 |
|---|---|
| 熔炉/高炉/烟熏炉 | 产物仅入库一次；人为造成输出不足时，已产出物不丢失且输入不会退款 |
| 营火 | 掉落被捕获后仅入库一次，不会被外部磁铁或拾取逻辑提前夺走 |
| 石切机/锻造台等虚拟路径 | 虚拟产物正常入库一次，配方结果数量与合同一致 |

验证完成后，下一步才是把同样的显式合同判定接入图执行；不应为了统一而让任何旧 delegate 自动进入新结算。

### 24.5 Vanilla 炉类输入缓冲第一阶段

熔炉和高炉的单操作输出结算已经完成游戏内验证后，第一份类型 B 输入缓冲接入限定为原版命名空间的熔炉、高炉和烟熏炉：

- 新服务器配置 `enableVanillaFurnaceInputBuffer` 默认开启；关闭后立刻回到原来的一次一件路径；
- `vanillaFurnaceInputBufferLimit` 默认 64，范围 1-64；实际批次受输入槽堆叠上限、输出槽可容纳的配方结果次数和当前需求共同限制。该原生缓冲容量可在安全边界内高于 `craftingOperationsPerDispatch`，因为后者是通常 delegate 的调度上限，而这里仍只占用一个炉子租约；
- RSI 一次预留并放入整个批次，但原版炉子仍每个烹饪周期消耗 1 个。它减少的是预留、网络提取、入槽和重启次数，不会把炉子的处理时间压成一个周期；
- 燃料需求改为按整批烹饪周期计算并在启动前验证；无法提供足够燃料时，不启动该批并保持账本退款语义；
- 完成观测不再在第一个正确产物出现时结束，而是等待本批期望产量；运行中的 `activeFurnaceOperations` 与下一批规划值分离，后续的批次探测不能把一个已启动的 64 件炉子改回 1 件结算；输入已耗尽但产量不足时，结构化输出结算会保留真实产物并无退款失败，防止复制；
- 仅平铺单机调度会调用 `tryStartWithInputBuffer(...)`。递归图执行、并行组、砖炉和所有非原版命名空间兼容炉仍通过旧 `tryStartWithMaterials(...)` 一次一件启动，避免未验证路径隐式改变行为。

首轮游戏内验证应使用 2、8、64 等不同数量，并分别覆盖熔炉与高炉：确认输入槽会先显示整批数量后逐次减少，最终产物只入库一次，燃料按整批消耗；还应测试把 `enableVanillaFurnaceInputBuffer` 关闭后恢复逐件行为。通过后再考虑烟熏炉，并在单机缓冲稳定后评估多台炉子的并行缓冲和递归图执行接入。
