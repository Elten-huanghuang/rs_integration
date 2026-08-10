# Performance Implementation Status

## 2026-08-09 配方目录启动期编译

- `RecipeIndex` 与 `ImmutableRecipeGraph` 现在由同一次服务端安全捕获生成，不再先用 tick 预算分批建立索引、完成后再第二次遍历索引投影纯图。
- 初次启动在 `ServerStartedEvent` 返回前完成目录 generation；`/reload` 在新配方已经应用、向客户端同步前重建。索引与纯图全部完成后才发布 ready 状态，普通预览请求只读取已发布 generation，不参与构建，也不会通过提高 tick 预算追赶预热。
- `/reload` 会递增 `CraftPlanningRevision`，同时清理旧索引、纯图和计划缓存。新旧 revision 不能混用，修复了只按 `RecipeManager` 对象身份判断时可能继续读取旧索引的问题。
- 普通 `CraftingRecipe` 的输出提取和纯图节点投影已合并到一个遍历中，避免重复调用 `getResultItem`。第三方 handler 仍在服务端加载线程读取，不能为了表面上的异步化把实时 Forge/模组对象送入后台线程。
- `/rsi_debug perf` 新增 `recipeCatalog=次数/平均/最大`、其中的 `graph` CPU 和配方数量；完成日志同时记录最慢配方 ID 和耗时。原日志中的几十秒是 tick 节流后的墙钟等待，新指标记录实际构建 CPU。
- 需求树路由走查改用可回滚库存账本和按物品 ID 的匹配索引，不再为每个候选分支复制完整库存快照；`/rsi_debug perf` 同时记录 `demandTree=次数/平均/最大`，用于区分路由前置成本与 typed resolver 成本。
- 当前代价是世界加载或 `/reload` 会增加一次真实目录构建耗时。这是显式加载成本，不会延后到玩家第一次打开配方树；具体耗时必须在目标整合包中复测后再决定是否需要针对最慢 handler 做专项优化。

已完成：

- 计划节点上限与进度协议上限统一为 4096。
- 共振盘白名单索引、状态变更同步和扫描计数。
- 完整进度包与增量进度包协议。
- 计划构建耗时、计划/进度包体积、网络解析缓存命中、delegate 观测耗时统计。
- 计划缓存结构化 key、网络解析缓存容量保护、FTB pending 队列容量保护。

尚未实施：

- DAG-only 计划协议及旧客户端兼容迁移。
- 大型核心类拆分。

进行中：

- 已加入 `PlanningSnapshot` 值对象和 `AsyncPlanningCoordinator`：快照输入不可变，按玩家合并请求并取消旧任务；后台异常、取消、过期重验证失败和提交异常都会通过服务器执行器回滚。
- 该调度器尚未替换 `GenericCraftPacket.tryBuildPlan`。在迁移完成前，现有规划仍保持同步执行，避免第三方配方反射对象越过线程安全边界。
- 预览请求的退出、停服和同玩家新请求取消已接入调度器；后台上下文访问非原版配方结果提取时会 fail-closed，触发回滚/同步回退，而不是执行第三方反射。
- `tryBuildPlan` 已在主线程采集 `PlanningSnapshot`，提交前重验配方版本、玩家连接状态、背包/RS 可用物品、网络身份和机器绑定；计划缓存命中也必须通过相同快照指纹校验。
- 已加入不持有 Minecraft/Forge/RS 对象的 `ImmutableRecipeGraph` 与 `PureRecipePlanner`，覆盖库存扣减、标签候选、递归批次和循环终止；真实 `RecipeIndex` 已通过 `ImmutableRecipeGraphProjector` 在主线程投影为值对象图。
- 已加入 `ImmutableRecipeGraphProjector`，并将原版配方图写入 `PlanningSnapshot`；投影发生在主线程，后台只读取值对象。尚未将该结果替换现有 `CraftingResolver` 的最终计划输出。
- 原版不可变配方图现在按 `RecipeManager` 身份和规划修订号共享；普通预览及计划缓存复用同一份图，数据包重载和停服会清理缓存，避免每个请求重复全量投影并保留独立大图。性能快照记录投影调用/命中及重建平均/最大耗时。
- 异步纯规划结果与产生它的 `PlanningSnapshot` 绑定返回；服务端组装前重验原始库存、网络、绑定、请求代次和强制候选，取消或过期任务不会再触发同步回退。命中计划缓存时也会在提交后台任务前直接返回。
- `PurePlanAdapter` 从逐步骤扫描完整配方图改为一次建立配方 ID 集合，回调适配复杂度由步骤数乘配方数降为线性。
- `GenericCraftPacket.tryBuildPlan` 已按配方类型切换：原版配方先走快照值图和后台纯规划，结果回主线程复用旧计划组装；特殊/第三方配方、投影缺失和后台异常走同步兼容路径。
- 阶段 D 已提取 `PlanCache`、`PlanRequestService`、`PlanningStateValidator` 和 `PlanResponsePublisher`；错误响应现在保留客户端 `requestId`。完整 `PlanResponse` 数据组装仍位于 `GenericCraftPacket`。
- 阶段 D 已提取 `PlanMaterialBill`：标签候选合并、严格 NBT 库存统计、净需求可行性、树形毛需求展示和过量产物统计不再由 `GenericCraftPacket` 直接承担；模组特有警告与最终 `PlanResponse` 数据组装仍待迁移。
- 已从 `AsyncCraftChain` 提取有状态 `CraftProgressPublisher`，统一完整包/delta 选择、重复负载抑制、终态单次发送和节点变化计算。
- 已提取实质性的 `TerminationService`：拥有退款/交付/静默策略、固定清理顺序、步骤失败隔离和最终审计；`AsyncCraftChain` 仅通过 `Actions` 回调提供具体资源操作。
- 平铺/DAG 执行策略现在返回带原因的 `Decision`，并支持 `ModType.requireFlatExecution(reason)` 元数据；策略会检查自包含图中的全部 `ModType`，而不是只检查追加终端步骤。
- `ModType` 现在显式记录 `UNAUDITED`、`GRAPH_SAFE`、`FLAT_REQUIRED` 三种图执行审计状态。未审计类型默认 fail-closed 到平铺；启动期只确认显式 ID 允许名单内的已审计标准类型，名单外的新注册类型不会被批量放行。现阶段只有 `custom_gui` 有可验证的专属平铺约束：其机器选择和 GUI 状态没有进入 DAG 节点/材料边。
- 追加终端配方仍因终端操作不在图中而走平铺兼容路径；这是图形不完整约束，不属于某个 `ModType` 的能力限制。
- `ExecutionEquivalence` 现在严格拒绝未知 `ModType`，并按拓扑顺序比较节点数量、配方 ID、ModType、执行次数、recipe type、候选配方/类型、infer mode 和 synthetic input/output。它证明的是可投影执行步骤元数据一致；真实库存扣取、输出和回滚仍由图校验及执行级测试分别保证，不能把该 helper 单独视为完整事务等价证明。
- 图到平铺步骤的投影已统一到 `ExecutionEquivalence.projectFlatSteps`，`AsyncCraftChain` 不再维护另一份转换实现。生产终端图完成组合后会与预期 legacy 步骤做等价校验，校验失败时保守回退。
- `RootDemand` 已保留 `DemandRole`；resolver 会把 `IngredientSpec` 的角色写入根需求，`TerminalGraphComposer` 再原样写入终端节点，避免催化剂/可复用输入在图合成时被无条件改成 `CONSUMED`。测试覆盖了催化剂角色穿透。
- 新增事务级 DAG/平铺结果对照：平铺侧使用生产 `StackPoolTransaction`，DAG 侧使用生产 `MaterialBroker`，已覆盖成功消耗、催化剂返还以及提交后失败退款。该测试验证两套事务原语的资产结果；真实 Forge 服务器、具体第三方机器 delegate 的运行级 smoke test 仍属于发布验证范围。
- 新增长链和并行 DAG 模拟：512 个机器节点严格串联完成，验证深依赖解锁、精确 NBT 材料传递以及预算/租约/材料所有权无残留；另有 256 节点、16 层乘 16 并行宽度的分层图，实测峰值同时持有 16 个机器租约和 16 个 operation permit，并完成全部节点。8 个完全独立节点也能同时达到配置并行上限 8。
- 静态输入且输出已知的重复终端配方已迁移到完整 DAG；`repeatCount > 1` 不再作为平铺原因。`TerminalGraphExecutionPolicy` 目前保留 infer mode、运行时选材、未知输出和非确定主产物四类结构化 fallback 原因。
- `TerminalGraphComposer` 目前只接受确定主产物。通用图模型虽有 `OutputKind.DYNAMIC`，但图终端根分配与生产发布仍使用精确 `MaterialKey`，因此 Aetherworks 多候选结果等配方继续走平铺，直至运行时物料键进入 DAG。
- 原版 `CraftingRecipe` 的前置解析与统一 resolver 现在保留 `CraftPlanGraph`，标准机器中间链及输出到玩家背包的异步链会组成终端节点、通过等价校验后走 DAG；不再先降为 `List<ResolutionStep>`。
- 含机器中间节点的 Smithing 链也改为保留输入图并组成终端节点。纯 GENERIC 中间步骤仍沿用同步执行；合成 taint 步骤和组合校验失败继续走 legacy flat fallback。
- 新增 `LegacyFlatExecutionService`，统一显式 legacy 步骤链的构造、目标输出配置、提交、完成回调接线和启动通知；`GenericCraftPacket` 不再直接散落创建普通平铺链。
- 新增有界 `LegacyExecutionMetrics`：按固定原因枚举和已注册 ModType 统计回退次数，recipe ID 只进入诊断事件；性能快照会显示 infer、运行时选材、未知输出、非确定输出、taint、ModType 限制和图组合拒绝的实际命中量。
- 完整图因 `ModType.requireFlatExecution` 选择平铺时同样会记录真正触发约束的图节点类型，而不是误记终端 GENERIC 类型。
- `GenericCraftPacket` 中所有生产平铺启动已统一经过 `LegacyFlatExecutionService`，且必须携带固定原因；标准 FTB Quest、Spawner Upgrade、原版递归链、机器终端和 Smithing 链均使用 graph。剩余 adapter 集合已收敛为 `custom_gui`、infer、运行时选材、未知/非确定输出、taint 合成步骤及图组合校验失败兜底。
- 异步规划线程池已改为服务端配置控制的固定 worker 和有界队列（默认 4/64）；同玩家旧请求会取消底层 `Future` 并从等待队列移除，纯规划在目标查找、库存投影和递归求解中协作响应中断。队列满时返回可重试错误，不会在服务端线程同步代跑；停服会执行 `shutdownNow()`，配置重载会安全替换 executor，性能快照记录提交/完成/拒绝/取消、活跃数、队列深度和执行耗时。
- `PureRecipePlanner` 已从逐根贪心递归改为跨全部根需求的有界事务式回溯，能撤销先前配方或标签候选选择，避免可行计划误判。不可变图附带 recipe-ID 索引；搜索状态和失败缓存上限由服务端配置统一控制（默认 65,536/8,192），并区分 `UNRESOLVABLE`、`STEP_LIMIT`、`SEARCH_LIMIT`。非成功结果不暴露半成品步骤或库存，继续由 typed resolver 保守兜底；性能快照记录展开状态、回溯、缓存命中和两类预算耗尽。
- 新增纯 DTO 边界 `PlanResponseDraft`：`GenericCraftPacket` 收集完实时机器/绑定/Embers 状态后生成不可变草稿，由规划 worker 完成最终响应冻结；回到服务端线程后再次重验玩家、配方修订、库存、网络、绑定和请求 generation，才写入缓存并发送。目标、步骤输入输出、基础物品、点击产物、Embers 数组及 DAG 视图均深拷贝，材料和机器类型保持插入顺序且只读，避免计划缓存被后续可变 `ItemStack`、数组或集合污染。
- 新增固定随机种子的纯规划性质测试：200 个小型 DAG 与独立广度优先穷举器对照可行性，成功计划按步骤重放并要求剩余库存完全一致；1,200 层深链由 512 层调用深度保险转为 `SEARCH_LIMIT`，避免 JVM 栈溢出并保守回退 typed resolver。
