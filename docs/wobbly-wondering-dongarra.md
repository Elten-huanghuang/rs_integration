# 跨步骤 DAG 并行 Crafting 完整实施计划

## Context

当前系统把依赖与执行顺序压缩在 `List<ResolutionStep>`，并由 `AsyncCraftChain` 的单一 `currentStepIdx/currentDelegate/ledger/virtualInventory` 串行推进。`ParallelCraftGroup` 只解决同一节点内部的多机器 operation 并行。目标是让互不依赖的分支并行运行并在汇合节点等待，同时保持材料守恒、部分成功结算和失败不刷物。

**结论：可行，但应分阶段实施。** 第一版 DAG 必须是带物料来源（provenance）与定量 allocation 的物料流图，而不是只增加 `dependsOn`；执行并发先固定为 1，验证图与所有权后再开放并行。首轮不做跨停服持久化或崩溃恢复。

预计单人约 **43～64 人日（9～13 周）**。建议阶段 0～4 分别提交和验收。

## 范围与非目标

包含：权威物料流 DAG、节点独立 runtime/ledger、chain 级 reservation broker、provenance asset store、machine/capture lease、串行 DAG→独立叶子→完整多层并行、统一失败/取消/断线/停服、craftId、进度/取消网络包、DAG UI 和守恒验证。

不包含：跨停服恢复、重连续跑、客户端提交权威图、运行中自动换配方、无法证明机器状态时的乐观退款。断线/停服仍终止任务，但按 `STOP_SCHEDULING → DRAIN/CLEANUP → SETTLE/REFUND → DELIVER` 收敛，而非立即整体退款。

## 核心设计

### 显式物料流图

新增 `crafting/graph/`：

- `CraftPlanGraph`：version、nodes、allocations、root demand、unresolved demands、稳定拓扑序；
- `CraftNode`：实例 `NodeId`（不能用 recipe ID）、recipe/mod/type、executions、alternatives、policy、inputs、outputs；
- `InputDemand`：InputPortId、原始 ingredient/spec predicate、数量、角色和展示值；
- `OutputDeclaration`：OutputPortId、StackKey、数量、primary/secondary/remainder/synthetic 类型；
- `MaterialAllocation`：consumer port、source、实际 StackKey、数量；source 为 `InitialPool`、`ProducerOutput` 或 `Unresolved`；
- `RootDemand`：最终请求的显式 sink；
- `CraftPlanValidator`：校验引用、无环、端口满足、producer 不超额、root 数量和拓扑序。

依赖从 producer allocations 派生；allocation 是权威来源。规划期只区分初始池与节点产物；真实材料来自 RS、玩家还是绑定网络，由运行时 ledger 决定。

### Resolver 与 provenance

扩展 `ResolutionContext`：保留 `counts`/inverted index 作为性能缓存，新增带 `SupplyRef` 的 planning lots；`consumeMatching()` 返回具体 allocation；`add()` 携带来源；undo 同时回滚 lot/node/allocation/unresolved/ID。

继续复用 `RecipeIndex`、`CandidateEngine`、DFS、net gain、protected reserve、cycle/ping-pong 和 checkpoint。`StepExecutor` 先创建可回滚的 NodeDraft/ports，再递归分配输入，成功后登记多种 outputs。best-effort 缺料生成 `UnresolvedDemand`。

停止按 `ingredient.getItems()[0]` 合并需求。默认每个 slot/spec 一个 input port；只有 canonical predicate、NBT policy、matcher type、role、remainder policy 完全相同时才合并。

抽出统一 `MaterialMatcher`，供 resolver、ledger、asset broker、executor 和 UI availability 共用，底层复用 `IngredientMatcher`、SlashBlade/NBT 特殊规则。执行时重新验证 initial allocation；recipe/matcher revision 变化则拒绝旧计划。

### DAG runtime

保留 `AsyncCraftChain` facade，内部逐步拆为：

- `DagScheduler`：completed/ready/running/blocked、indegree、dispatch budget；
- `CraftNodeRuntime`：节点状态、独立 ledger、delegate/group、reservation、capture、timeout、进展和失败；
- `MaterialBroker`：server thread 串行仲裁所有节点 reserve/commit；
- `MaterialAssetStore`：带 producer/operation/state 的实际 lots；
- `MachineLeaseRegistry`：dimension+pos+logical type 唯一 owner；
- `CaptureLeaseRegistry`：capture 绑定 craft/node/operation，重叠且 predicate 可能相交时拒绝并行；
- `TerminationCoordinator`：统一终止流程。

节点状态：`BLOCKED → READY → RESERVING → RESERVED → DISPATCHING → RUNNING → SETTLING → SUCCEEDED`；停止路径为 `STOP_REQUESTED → DRAINING → CLEANUP → FAILED/CANCELLED`。

`ParallelCraftGroup` 保持为单 DAG 节点内部 worker pool，复用动态续领、ReservationToken、增量 settle、stop-dispatch 和 draining；不负责跨节点调度。

### Broker 与守恒

节点启动顺序固定：校验 producer lots → 原子 reserve lots → 节点 ledger reserve initial inputs → 获取 machine/capture leases → commit ledger → assets 标记 committed → 调 delegate start → 确认产物后先 settle 输入 → 发布 output lots。

调用 start 后进入“可能已消费”区，不能凭 false/异常直接恢复虚拟材料。

关键不变量：

1. 每个 lot 单位最多一个 active reservation；
2. allocation 不是物理扣料，运行时必须重验；
3. 产物确认后输入先 settle，之后绝不退款；
4. 未 settle 输入可退款，但对应未确认 capture 不得交付；
5. 下游已 commit 的 producer 数量不得再交付为 surplus；
6. 只有从未 dispatch 的 virtual debit 可无条件恢复；
7. ledger 保存并退款 commit 时的真实 extracted fragments，不用 `template × count` 重建；
8. cleanup/refund/capture close/lease release/delivery/callback 幂等；
9. 每份材料最终归类为已消费、已交付、仍在机器、可退款或明确 unknown，不能静默缺账。

## 实施阶段

### 阶段 0：安全基线（4～6 人日）

- `onDone` 改 additive listeners，manager cleanup 与业务 callback 分离；
- 引入 stable craftId 和按 ID 查询；
- 合并 abort 公共骨架，用 settlement policy 区分退款，修复 player null 时 logout/stop 漏退；
- manager cleanup 完成前不提前移除任务；停服逐任务 best-effort；
- 统一 matcher，取消不安全 ingredient grouping；
- ledger 保存实际 extracted fragments，增加幂等 terminal guard；
- 补 manager/chain/ledger/group characterization tests。

退出条件：offline、network invalid、server stop 守恒；重复 abort/refund/callback 无副作用；现有串行行为不变。

### 阶段 1：provenance DAG，仍串行（10～14 人日）

- 新增 graph 模型/validator/planning lots/graph resolver；
- 改造 `ResolutionContext`、`ensureIngredient()`、`StepExecutor`，记录 ports、allocations、outputs、root、surplus、unresolved；
- 旧 `resolveSteps...()` 由 graph 稳定拓扑序投影，兼容旧调用；
- DAG executor 使用 node runtime + asset store，但 `maxConcurrentNodes=1`；
- vanilla 节点同样经过 node reservation/settlement；
- feature gate 保留旧 flat executor，节点内 group 可暂时关闭。

退出条件：fork/join/diamond 定量正确；库存+producer 混合供给、secondary/remainder、OR/NBT 正确；serial graph 与旧 executor 的库存变化等价；真实 3 层链和共享产物链通过。

### 阶段 2：独立 ready 叶子并行（8～11 人日）

仅并行：互相无依赖、无 lot/machine/capture 冲突、非玩家变换、非 infer、delegate 声明安全 shared-material/cleanup、非 legacy 自提取的节点。未知 delegate 默认 exclusive。

实现节点独立 ledger、broker 串行 reserve/commit、machine/capture registries、多 running node tick；同 tick 顺序为 observe 全部 → settle → 更新 ready → 有限 dispatch。此阶段可继续关闭节点内 group。

退出条件：两个真实机器同时运行；库存竞争不双扣；一个叶子失败不抹掉另一个的 settled 产物；机器/capture 冲突安全阻塞；取消/断线/超时完整收敛。

### 阶段 3：完整多层 DAG + 节点内工作池（10～15 人日）

- settled producer lot 增量解锁下游，允许跨层重叠；
- 恢复 `ParallelCraftGroup`，scheduler 分配 leases/机器预算，group 节点内续领；
- 每 tick/per craft dispatch cap、running cap、delegate capability allowlist；
- 分离 node no-progress、draining hard、chain global timeout；
- failure 提升 scheduler epoch，旧 callback 不得新 dispatch，但 in-flight 可 settle；
- 保留全局并发=1及按 mod 禁用的回退。

退出条件：三层跨层并行；节点内多机和另一节点并行并存；首个失败后零新 dispatch；随机时序守恒测试和长期压力测试无 manager/lease/capture 残留。

### 阶段 4：DAG UI、进度与取消（5～8 人日）

- manager 主索引改为 CraftId；
- `CraftProgressSnapshot` 含 sequence、chain/node/operation 状态；
- 新增 start/progress/terminal S2C 与 cancel/status C2S；cancel 只传 craftId，服务端验 owner/状态/限流；
- PlanResponse 发送 NodeId/ports/allocations，客户端不提交权威图，confirm 后服务端重算；
- `PlanTreeModel` 按 NodeId/edge 建模。树形 renderer 可复制 view reference，但共享同一逻辑 NodeId；
- 展示 initial、共享边数量、unresolved、secondary/surplus、running/blocked/completed；
- packet set/wire 变化时 protocol 6→7，并限制 decode 数量和字符串长度。

退出条件：共享 producer UI 正确；迟到 sequence 丢弃；非 owner cancel 拒绝；terminal 一次；取消后无 active runtime/lease/capture。

## 终止语义

普通失败/取消：记录首因并 STOPPING → 禁止新 dispatch → 未启动节点释放 reservation → running nodes draining → 已确认 operation settle 并发布实际产物 → exactly-once cleanup → 仅 refund refundable 的真实 fragments → 交付 settled 且未消费 lots → 释放 leases/captures → terminal callback/manager remove。

断线：请求 `ABORTED_OFFLINE`，不再依赖 ServerPlayer；退款优先原 network，再 craft network，最后明确 world drop；cleanup 完成才删除，不等待重连。

停服：snapshot 并 stop scheduling，不先 clear manager；同步 quiesce 一次（observe、settle、close capture、cleanup、refund provable、deliver）；不等待未来 tick；未知 in-flight 禁止乐观退款并高等级记录；逐任务异常隔离，最后清 registries。

## 关键文件

Resolver：
- `src/main/java/com/huanghuang/rsintegration/crafting/CraftingResolver.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/ResolutionContext.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/StepExecutor.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/RecipeIndex.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/CandidateEngine.java`
- 新增 `src/main/java/com/huanghuang/rsintegration/crafting/graph/*`

Runtime：
- `src/main/java/com/huanghuang/rsintegration/crafting/AsyncCraftChain.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/ExtractionLedger.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/loadbalancer/ParallelCraftGroup.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/batch/IBatchDelegate.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/CraftOutputInterceptor.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/AsyncCraftManager.java`

UI/network：
- `src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/plan/PlanResponse.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/plan/PlanResponsePacket.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/tree/PlanTreeModel.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/plan/CraftingPlanScreen.java`
- `src/main/java/com/huanghuang/rsintegration/network/packet/NetworkPacketIds.java`
- `src/main/java/com/huanghuang/rsintegration/network/packet/NetworkHandler.java`

## Verification

### JUnit / 纯逻辑

Graph/resolver：fork/join/diamond、共享 producer、initial+producer 混合、secondary/remainder/surplus、同 recipe 不同 nodes、NBT/OR、candidate rollback、unresolved，以及 validator 对环/悬空/超配/root 不足的拒绝。

Ledger/broker：两个节点争库存、tag/OR 实际 stacks 原样退款、partial rollback、settle 后 abort 不退款、各失败注入点、offline fallback、幂等 settle/refund。

Scheduler/fake delegate：串行拓扑、ready 并行、producer 解锁、失败后零 dispatch、drain 后 terminal、timeout/cancel/logout/shutdown、stale epoch、listeners/manager removal 一次。

Lease/capture/codec：machine 唯一 owner、lease generation、capture overlap/幂等 close、shutdown 清零、graph/progress round-trip/bounds/sequence/owner cancel。

运行 `./gradlew test`，并保持现有 OperationQueue、machine dedup、production matching、rate limiter 测试通过。

### 真实 Forge 端到端

覆盖：全 vanilla 三层链；vanilla→mod→final；五独立叶子并行汇合；节点内多机与另一节点并行；跨层并行；共享产物/secondary/remainder；NBT/OR；world output/磁铁/外部提取/capture overlap；中途 cancel/logout/controller removal/chunk unload/machine failure/network full/server stop；长期压力后所有 runtime/ledger/leases/captures 清零。

每场景前后核对 network、player、machine slots、world entities、asset store、ledger 和 leases。统一守恒式：

`初始网络 + 初始玩家 + 初始机器 + 合法生成产物 = 最终网络 + 最终玩家 + 最终机器 + 世界掉落 + scheduler 持有的 settled assets`

不能只以 UI 完成或目标产物出现作为成功标准。

## 交付风险控制

- 阶段分别提交，不在一个提交同时改 resolver、并发、wire 和全部 delegates；
- graph serial/并发=1 始终作为生产回退；
- 跨节点并行按 delegate/mod allowlist 渐进开放；
- capture 冲突宁可降并发，不猜 ownership；
- graph/packet 设硬上限并保留 resolver budgets；
- 每阶段通过守恒测试和真实机器验收后才开放下一档；
- 持久化/崩溃恢复另立后续项目。
