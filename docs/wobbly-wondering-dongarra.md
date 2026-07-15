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

# 2026-07-15 完成度复核（当前结论）

> 本节是当前工作树的权威状态。下方原“结合文档逐项看”开始的长篇审核保留为历史记录，其中关于 broker/lease 未接线、DAG wire、status 查询、operation 进度、per-tick budget 和并发禁用配置缺失等判断已经过期。

## 当前总体判断

| 范围 | 当前完成度 | 结论 |
|---|---:|---|
| 阶段 0：安全基线 | **98%** | additive listener、稳定 craftId、离线退款和 termination audit 已接线；server stop 会先执行一次无新 dispatch 的 observe/publish/settle quiesce，真实专服 teardown 仍待验收 |
| 阶段 1：串行 provenance DAG | **96%** | graph、allocation、validator、broker admission、node ledger、exact initial/producer checkout 与实际产物发布已接生产路径；graph vanilla 已进入 logical operation kernel 生命周期 |
| 阶段 2：独立叶子并行 | **90%** | executor、独立 runtime、material/machine/capture 冲突、exclusive gate、三态 preparation 和 server/craft budget 已接线；真实 Forge 双机矩阵未验收 |
| 阶段 3：跨层并行与节点内工作池 | **86%** | 增量 publication/readiness、epoch、dispatch cap、group 动态续领和 operation scope 已接生产路径；group transient resource retry 已修，生产压力验收仍缺 |
| 阶段 4：DAG UI、进度与取消 | **88%** | graph DTO/codec、NodeId/edge tree、node+operation progress、cancel/status/reconnect sync 已完成；UI 状态粒度和真实交互验收仍不完整 |
| Verification | **82%** | clean JUnit 与关键生命周期/守恒组件测试通过；新增 logical operation、no-dispatch quiesce 和 500 次 deterministic material/operation/lease 状态压力测试；完整 Forge 守恒矩阵仍缺 |

严格按整份计划（包含真实 Forge 验收和压力矩阵）计算，当前总体约 **89%**。若只计算已接线代码与普通 JUnit，可视为约 **96%**；不能把后者等同于可发布验收。

## 已完成并有生产接线证据

1. `CraftPlanGraph`、ports、allocations、roots、unresolved、稳定拓扑序和 `CraftPlanValidator` 已存在，resolver planning lots 保留 initial/producer provenance 与 NBT。
2. `AsyncCraftChain` 从 allocations 构造 `graphRequests`，graph vanilla、mod node 均经过 `NodeAdmissionCoordinator` 和 `MaterialBroker` reservation；initial 与 producer checkout 都保留规划绑定的实际 `ItemStack`/NBT，ledger 按 exact identity 预留和提取。
3. `NodeOutputAccumulator` 按 `OutputPortId` 增量确认并发布实际 fragments，terminal 输出声明短缺会 fail-closed；surplus 与 downstream-held producer lot 分离。
4. `ConcurrentNodeExecutor` 已按 observe all → publish all → terminal → bounded dispatch 执行；`DagScheduler.refreshBlocked()` 由 broker 可 checkout 数量驱动跨层解锁。
5. server-scoped machine/capture registries、craft/global `OperationBudget`、`OperationResourceCoordinator` 和原子 `CaptureSession` 已实现；flat single/group 已接入统一 scope。
6. executor 已有 node running cap、per-tick/per-craft dispatch cap、epoch stale-completion 防护；未知 delegate 默认 exclusive，并支持按 mod 禁用并发。
7. `ParallelCraftGroup` 支持 operation 动态续领、每次真实 start 计 lifetime budget、增量 settle、stop-dispatch/draining 和 operation scope 释放。
8. `PlanGraphView`、graph packet codec、`PlanTreeModel` 的 NodeId/edge 建模、node/operation progress、status request、reconnect sync、cancel owner/rate-limit 已存在。
9. broker、scheduler、lease、budget、capture scope、graph conservation、graph UI model、packet bounds/status/progress 均已有纯逻辑测试。

## 仍未完成或不能宣称完成

### 1. exact initial physical checkout 已完成，仍需 Forge 实物验证

initial source 现在由 `MaterialBroker.Checkout.initialStacks()` 输出规划绑定的实际 fragment，graph vanilla 与 mod 节点均通过 `ExtractionLedger.reserveExact()` 按 item+NBT 身份预留并在 commit 时精确提取，不再按宽 Ingredient 二次选型。broker checkout 的 initial/producer 分离已有单元测试；网络、玩家背包和绑定网络的真实提取/退款仍需纳入 Forge 守恒矩阵。

### 2. graph vanilla 已纳入 logical operation kernel；server stop 具备一次性 quiesce

`OperationExecutionKernel` 现在同时支持物理 resource scope 和无机器资源的 logical session。graph vanilla 使用节点独立 ledger、broker exact checkout，并经过统一 commit → start → output validation → settle 生命周期；执行期间禁止回退到网络按 Ingredient 自由扣料。

server stop 会先停止 graph dispatch，再执行一次 observe all → publish → completion settle，且不会启动新节点；flat delegate 也会做一次 completion observation，并在可证明 DONE 时收集和 settle。仍在运行且无法证明完成的 operation 保持 IN_FLIGHT/UNKNOWN，不做乐观退款。真实专服 teardown 仍需验证第三方机器的 observe/cleanup 语义。

### 3. preparation 显式三态已接 graph admission，group 资源冲突现已保持 RETRY

`IBatchDelegate.prepare()` 返回 READY/RETRY/FATAL；旧 delegate 的 boolean validation 失败默认保守映射为 RETRY，第三方 delegate 可覆盖该方法声明永久 contract failure。本次复核修复了 `ParallelCraftGroup` 的一个生产问题：child operation 获取 budget/machine/capture scope 失败时不再先消费 operation id 并让整个 group draining，而是保留 queued 并在后续 observe tick 重试。各第三方 delegate 的精确永久错误分类仍需渐进补充。

### 4. 普通 JUnit 证据有效，但生产编排和 Forge 证据仍不足

clean JUnit 已覆盖 kernel、resource coordinator、broker、executor、termination coordinator、preparation contract 和 graph conservation。新增的 deterministic stress harness 连续执行 500 次 reserve/release、commit/settle/refund 和 operation machine lease 生命周期，每轮断言 broker 无 held claim、budget active=0、machine/capture registry=0，并核对 available 数量。普通 JVM 测试侧的已知缺口已经收敛；当前没有自动 Forge GameTest 场景，因此不能据此宣称第 182～190 行实物矩阵已完成。

## 剩余工作的现实拆分

1. **执行 Forge exact checkout 与完整守恒矩阵**：覆盖 NBT variant、网络/玩家/背包/绑定网络、双机、跨层、shared producer、world output、磁铁/外部抽取、cancel/logout/chunk unload/server stop。
2. **增加自动 Forge GameTest/专服场景**：让实物守恒矩阵可重复执行，而不是依赖人工地图验收。
3. **为重点第三方 delegate 覆盖 `prepare()`**：把可证明的 recipe contract mismatch 标成 FATAL，其余机器忙/未加载状态保持 RETRY。
4. **补 UI 状态细节与 termination report 可视化**：属于发布打磨，不阻塞代码侧守恒。

普通代码主路径已基本收敛。剩余工作以 Forge GameTest、第三方机器集成和长期压力验收为主，估算约 **4～8 人日**；发布结论必须以该实物矩阵通过为准。

---

## 历史审核（已过期，仅供追踪）

结合文档逐项看，以及我们刚完成的 8 轮加固，**核心图模型和串行执行已经完成，但文档的最终目标“安全的跨节点并行 + DAG UI”还没有达成**。

## 总体判断

| 范围 | 当前完成度 | 论 |
|---|---:|---|
| 阶段 0：安全基线 | **约 90～95%** | 基本完成 |
| 阶段 1：串行 provenance DAG | **约 75～85%** | 主体可用，但未完全按 asset-store 设计执行 |
| 阶段 2：独立叶子并行 | **约 65～70%** | 调度骨架完成，资源仲裁未接线，因此生产上保守串行 |
| 阶段 3：完整跨层并行 | **约 45～50%** | 增量解锁/工作池部分存在，安全并行尚未落地 |
| 阶段 4：DAG UI/进度/取消 | **约 55～60%** | 进度取消链路完成，DAG 图形建模基本未做 |
| Verification | **约 55～60%** | 逻辑测试增加到 111 个，但真机守恒验收尚缺 |

如果严格按整份计划验收，我认为当前总体大约 **65% 左右**。

---

# 一、真正的大缺口：运行时还不是权威 asset-flow executor

这是最关键的未完成部分，对应文档：

- `MaterialBroker`：文档第 49、61 行
- `MaterialAssetStore`：第 50 行
- machine/capture lease：第 51～52、61 行
- 阶段 1～3：第 96、106、113 行

目前虽然已有：

- `MaterialBroker`
- `NodeAdmissionCoordinator`
- `MachineLeaseRegistry`
- `CaptureLeaseRegistry`

而且都有单元测试，但它们仍然**没有完整接入 `AsyncCraftChain → ConcurrentNodeExecutor → CraftNodeRuntime` 的真实执行路径**。

当前真实材料流主要还是：

```text
每节点 ExtractionLedger
+ chain.virtualInventory
+ committedVirtual 快照
```

而不是文档设计的：

```text
planning allocation
→ MaterialBroker reserve
→ node ledger reserve initial
→ machine/capture lease
→ broker commit
→ delegate dispatch
→ input settle
→ publish actual output lots
```

因此仍缺：

1. 从 `CraftPlanGraph.allocations()` 生成每个节点的 broker request；
2. 把 initial pool 和 producer output 发布为 broker lots；
3. dispatch 前由 `NodeAdmissionCoordinator` 原子获得：
    - material reservation；
    - machine lease；
    - capture lease；
4. node ledger commit 后 broker token 才 commit；
5. delegate 启动失败时区分：
    - dispatch 前：release；
    - dispatch 后：不能乐观退款；
6. 成功确认时：
    - settle input reservation；
    - 发布真实 output lots；
7. 下游必须消费明确 producer lot，而不是仅从共享 `virtualInventory` 匹配；
8. surplus 交付必须排除已经被下游 commit 的 producer 数量。

**这块没做完，就不能安全开放真正的跨节点并行。**

---

# 二、阶段 2：叶子并行仍未真正开放

对应文档第 102～108 行。

我们刚修了一个重要问题：

- 未声明并发安全的 delegate 现在会被 executor 强制 exclusive，而不是只 warn。

这让系统目前是**安全的**，但也意味着：

> `craftingMaxConcurrentGraphNodes > 1` 时，绝大多数 mod 节点仍会退化为串行。

真正缺的包括：

### 1. machine lease 接入真实 dispatch

目前 registry 存在，但节点启动前没有实际拿 lease；`CraftNodeRuntime.machineLease` 的设计也没有完整走通。

必须保证同一个：

```text
dimension + position + logicalType
```

一次只能有一个 craft/node/operation owner。

### 2. capture lease 接入真实 output capture

需要在 arm `CraftOutputInterceptor` 前取得 capture lease：

```text
craftId + nodeId + operationId
+ dimension + AABB + expected material predicate
```

可能重叠且 predicate 可能相交时，应把节点留在 READY，而不是启动。

### 3. shared-material capability 仍需进一步验证

`supportsConcurrentNodeExecution()` 目前是单一 boolean，计划要求的实际能力更细：

- 是否只使用 chain 预留材料；
- 是否会 legacy 自提取；
- offline cleanup 是否安全；
- capture ownership 是否明确；
- 是否支持 operation 级 settle；
- 是否能在 failure 后禁止新 dispatch。

当前 capability 不足以表达这些差异。

### 4. 两台真实机器并行的 Forge 验收未做

单元测试只证明调度器能同时持有两个 fake worker，不代表：

- 两台真实机器同时启动；
- 不抢同一材料；
- 产物不会被错误 capture；
- 一边失败不会影响另一边；
- logout/server stop 后所有东西收敛。

---

# 三、阶段 3：完整多层并行还缺主要能力

对应文档第 110～119 行。

## 已有

- `DagScheduler.succeed()` 能增量解锁依赖节点；
- `ParallelCraftGroup` 仍可做节点内部工作池；
- running cap 存在；
- node no-progress timeout、draining timeout、chain-global timeout 现在都有；
- 首次失败后 executor 停止继续调度的大体骨架存在。

## 仍缺

### 1. producer lot 增量发布不是权威物料流

目前“节点成功 → 下游 READY”主要是逻辑拓扑解锁。

计划要求的是：

> 只有已确认、已 settle、已发布的 producer lot，才能定量解锁下游。

必须防止这种情况：

```text
scheduler 标记 producer SUCCEEDED
但真实输出数量不足/被外部提取/capture 尚未确认
下游却已经开始
```

### 2. scheduler 没有真正分配 machine/capture lease 和机器预算

文档第 113 行要求：

- scheduler 分配 leases；
- scheduler 分配机器预算；
- ParallelCraftGroup 在节点内部续领。

当前 executor 只控制 node 数量，没有统一协调：

- 一个节点内部占了多少机器；
- 另一个节点还能否启动；
- 单 craft 最大 operation 数；
- 同 tick 最大 dispatch 数。

### 3. per-tick dispatch cap 缺失

当前有 running cap，但没有单独的：

- 每 tick 最大新节点数；
- 每 craft 最大并发 operation；
- 全服务器 dispatch budget。

大量 READY 节点可能同 tick 集中启动。

### 4. `graphEpoch` stale-callback 防护未完成

`graphEpoch` 字段仍基本是预留状态。

文档要求：

```text
failure → scheduler epoch++
旧 callback 即使迟到，也不能触发新 dispatch
但已经 in-flight 的 operation 仍可 settle
```

当前大部分执行是同步 server-thread polling，因此风险还没完全暴露；一旦接入异步 callback、capture callback 或 ParallelCraftGroup 动态续领，就必须让 callback 携带 epoch/generation。

### 5. 按 mod 回退/allowlist 缺失

文档要求：

- 默认未知 delegate exclusive；
- 按 mod 渐进开放；
- 可按 mod 禁用并发；
- 全局 cap=1 永远可回退。

目前全局 cap=1 有，但缺正式的：

```text
parallelDelegateAllowlist / denylist
parallelDisabledMods
```

或 capability registry。

---

# 四、阶段 4 的核心 DAG UI 尚未完成

对应文档第 121～131 行。

进度/取消/HUD 链路已经做得比较好：

- manager 按 CraftId 索引；
- started/progress/terminal；
- cancel C2S；
- owner 校验；
- 500ms cancel 限流；
- sequence 去重；
- HUD overlay；
- protocol 7；
- codec round-trip；
- PlanResponsePacket decode 数量上限。

但是文档的核心目标仍缺：

## 1. PlanResponse 未携带 DAG graph identity

当前还是：

```java
List<PlanStep>
Map<IngredientKey, Availability>
```

缺：

- `NodeId`
- `InputPortId`
- `OutputPortId`
- `MaterialAllocation`
- producer source
- initial source
- unresolved demand
- output kind
- root allocation

客户端因此无法知道：

- 两个消费者是否共享同一个 producer node；
- 某条边确切供应多少；
- 材料来自初始库存还是中间产物；
- secondary/remainder/surplus 的来源；
- 同一 recipe 是否是不同 node instance。

## 2. PlanTreeModel 仍靠 IngredientKey 猜边

计划要求按 `NodeId/edge` 建模。

当前若两个节点输出相同 ItemStack，或者同 recipe 被实例化多次，客户端只能按材料 key 猜 producer，可能：

- 错连边；
- 把两个 node 合并；
- 共享 producer 展开成独立副本；
- 显示错误的需求数量。

## 3. operation 级进度缺失

`CraftProgressSnapshot` 目前主要是：

- completedNodes；
- totalNodes；
- runningNodes；
- failedStep。

缺：

- 每个 node 的具体状态；
- BLOCKED/READY/RESERVING/RUNNING/SETTLING；
- operation 数量；
- operation running/completed/failed；
- machine identity；
- draining；
- unresolved/blocked reason。

## 4. status C2S 包缺失

计划要求 cancel/status C2S。目前 cancel 有，status 查询没有。

如果客户端：

- 中途打开 HUD；
- 重进界面；
- 丢失 started packet；
- tracker 重置；

无法主动按 craftId 查询当前状态。

## 5. UI 指标缺失

仍需显示：

- initial allocation；
- shared edge 数量；
- unresolved；
- secondary/remainder；
- surplus；
- blocked；
- completed/running；
- 同一逻辑 NodeId 的共享引用。

## 6. 字符串长度限制还没完全显式化

刚修了集合数量上限，但 `PlanResponsePacket` 里大量 `readUtf()` 仍用默认长度。

Minecraft `FriendlyByteBuf.readUtf()` 自带默认最大字符限制，并非完全无界；但若按文档严格验收，仍应针对：

- recipe ID；
- target name；
- warnings；
- missing；
- mod type；
- dimension；

使用明确业务上限，例如 `readUtf(256)` / `readUtf(1024)`，并在 encode 侧保持一致。

---

# 五、终止语义还有“实现级近似”，未完全按状态机建模

对应文档第 133～139 行。

现在功能上已经很保守：

- 先 stop scheduling；
- in-flight committed ledger 不退款；
- settled/queued/captured best-effort 回收；
- manager terminal callback 后移除；
- server stop 逐任务异常隔离。

但还没有完整实现计划中的显式状态机：

```text
STOP_REQUESTED
→ DRAINING
→ CLEANUP
→ SETLE/REFUND
→ DELIVER
→ TERMINAL
```

当前仍主要是 `AsyncCraftChain.terminate()` 里的同步顺序调用，没有独立：

- `TerminationCoordinator`
- shutdown quiesce result；
- unknown in-flight 分类；
- cleanup step result；
- operation-level settlement report；
- terminal reason/category。

特别是 server stop：

- 计划要求同步 quiesce 一次；
- observe、settle、close capture；
- refund provable；
- unknown 高等级记录；
- 清零所有 registries。

目前更接近 best-effort abort，不是完整的可审计终止报告。

---

# 六、测试与真实验收仍有不少缺口

文档第 168～190 行。

## JUnit 仍缺的重点

### Graph/resolver

- 单个 demand 同时由 initial + producer 混合供给；
- SECONDARY/REMAINDER/surplus；
- 同 recipe 不同 NodeId；
- NBT/OR graph resolution；
- candidate recipe rollback；
- serial graph 与 flat executor 库存等价。

### Ledger/broker

- tag/OR 的真实 extracted fragment 原样退款；
- reserve/ledger commit/asset commit/delegate start 各失败点注入；
- offline network → craft network → world drop；
- node success 后 settle，后续 abort 不再退款；
- producer lot 被下游 commit 后不可重复作为 surplus 交付。

其中真实 fragment 退款受当前 layer-2 环境限制，必须上 Forge 测试。

### Scheduler

- timeout/cancel/logout/shutdown 完整收敛；
- stale epoch callback；
- per-tick dispatch cap；
- exclusive + lease 冲突组合；
- group 节点内续领与跨节点并行共存。

### Lease/capture/codec

- capture close 幂等；
- shutdown 后 registry 归零；
- PlanResponse graph codec round-trip（现在 graph 数据还没传）；
- sequence 丢弃；
- non-owner cancel handler；
- status query round-trip；
- UTF 明确边界。

## 真实 Forge 端到端基本未系统验收

至少需要文档第 184 行的矩阵：

- 全 vanilla 三层链；
- vanilla → mod → final；
- 5 个独立叶子并行汇合；
- 节点内多机 + 另一节点；
- 跨层重叠；
- shared producer；
- secondary/remainder；
- NBT/OR；
- world output / 磁铁 / 外部提取；
- cancel/logout/controller removal/chunk unload；
- machine failure/network full/server stop；
- 长期压力后 runtime/ledger/leases/captures 全清零。

而且必须按统一守恒式核账，不能只看目标产物出现。

---

# 七、文档本身已有几处与现实不一致

建议后续把计划文档转为“计划 + 当前状态”，否则会误判。

例如：

1. “cancel 限流未实现”——实际上已有 500ms/玩家；
2. “chain-global timeout 缺失”——现在已补；
3. “decode 数量无上限”——现在已补 4096；
4. “capability gate 只 warn”——现在已强制 exclusive；
5. validator 的环/悬空/root 不足测试——现在已补；
6. codec round-trip——现在已补；
7. abort 三方法未合并——现在已合并。

---

# 最终论

按文档目标，剩余工作可以压缩成 **三个大项目**：

## A. 权威运行时物料流与资源仲裁

```text
MaterialBroker + asset lots
+ NodeAdmissionCoordinator
+ machine/capture leases
+ dispatch/settle/refund
```

这是跨节点并行真正可用的前提，也是最大缺口。

## B. 完整阶段 3 并发控制

```text
per-tick/per-craft dispatch budget
+ machine operation budget
+ epoch/generation
+ per-mod capability allowlist
+ ParallelCraftGroup 续领
```

依赖 A。

## C. DAG UI 与网络模型

```text
PlanResponse graph DTO
+ NodeId/ports/allocations wire codec
+ PlanTreeModel 按 edge 建模
+ per-node/per-operation progress
+ status query
```

可以与 A/B 并行开发，但 wire 变化需要 protocol 7→8。

### 推荐顺序

1. **先做 A：broker/lease 接线，但保持 `maxConcurrentNodes=1`**
2. 写 serial graph vs flat conservation 对照测试
3. 真机验证守恒
4. 再把 cap 开到 2，完成 B
5. 最后完成 C（DAG UI）
6. 做完整真机压力验收

也就是说：

> 现在“串行 DAG + 节点内并行”已经可用；文档真正没完成的是“权威 material-flow 驱动的安全跨节点并行”和“客户端能正确看懂 DAG”。

如果按原计划的工作量尺度估，剩余大约仍有 **20～35 人日**；其中 A+B 约 12～22 人日，C 约 5～8 人日，真机验收约 3～5 人日。人日。
