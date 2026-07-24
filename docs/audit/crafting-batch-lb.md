# 审查域：crafting-batch-lb（批处理 + 负载均衡）

范围：`crafting/batch/` (12 文件) + `crafting/loadbalancer/` (3 文件)
审查方式：静态代码审查，重点资源守恒（物品刷/丢）与并发正确性。
结论：**0 P0 / 0 P1**；2 P3。本域是并发设计最成熟的区域之一，退款/结算路径普遍遵循「先 settle 再 cleanup」原则。

---

## 关键正确性验证（无需修复，仅记录已确认安全的设计）

### ParallelCraftGroup 结算-清理顺序守恒正确
`settleCompletedOperation`（loadbalancer/ParallelCraftGroup.java:384-444）在**每一条**退出路径上都先 `settleReservation(operationId)` 再 `closeOperationResources`：
- L400-410：产物被外部提取（countMatching 不足）→ 先保存残余产物 + settle token，再 abandon/close/drain。
- L413-420：期望世界产物未捕获 → settle + drain。
- L423-437：正常完成 → settle + 保存产物 + 副产物，之后才 `onBatchFinished` 清理；即使 finish 抛异常也已 settle，不会把真实产物与输入退款配对。

这正是历史坑 [[bug_multistep_abort_item_loss]] 的正确对策：清理异常无法导致「产物已产出但输入被退款」的双记。

### safelyRecoverableVirtual 门控正确
`safelyRecoverableVirtual[operationId]` 在操作一旦分派（L291，`startWorker` 内、commit boundary 之前）即置 `false`。`drainQueuedMaterialsForRecovery`/`drainQueuedProducerMaterialsForRecovery`/`queuedReservationTokensForRecovery`（L467-498）只对仍为 `true`（即从未落到机器）的操作合成退款。已分派操作的虚拟输入不会被二次合成 → 无刷物。

### onBatchFailed 不导出未确认捕获
`onBatchFailed`（L545-564）对每个 worker 调 `drainCapture`（丢弃未结算捕获）而非导出，注释明确说明「未确认捕获属于未结算操作，cleanup 可能退回其输入，导出会重复」。与 settle 路径互斥，守恒正确。

### OperationQueue / LoadBalancer 单线程且纯
- OperationQueue（91 行）：`HashMap` 无锁，但仅由服务器 tick 线程访问（与 [[reference_graph_conservation_baseline]] 一致的单线程约束）。claim/complete/abandon 状态机自洽，缺 in-flight 时抛 IllegalState 而非静默。
- LoadBalancer（183 行）：无状态纯分发器，`distribute` 余数分配正确（前 remainder 台各 +1），0 操作机器被排除。无副作用。

---

## 发现清单

### [P3] GenericBatchDelegate.captureRepeatedCraftingOutputs 中途 assemble 返回空时读取上一轮 pendingResult（仅影响 NBT，不影响计数） ✅ 已修复

**修复时间**：2026-07-23（本次会话验证）

> ✅ **已于 2026-07-23 在工作区修复**（采用本条给出的修复方向）：`captureActualCraftingOutputs` 改为返回 boolean（assemble 是否真产出），循环内 `if (!captureActualCraftingOutputs(...)) return false;` 消除跨轮 `pendingResult` 依赖；单次路径 `tryStartSingleCraft` 忽略返回值、保留 computeResult 模板降级。补充：本条定级 P3（"仅 NBT、计数守恒"）在"模板==真实产物"的常规配方下准确；理论反例是「`getResultItem` 非空但 `assemble` 对该网格返回空」的自定义配方——那种情况旧逻辑会把 computeResult 模板 ×N 当产物刷出（真刷物），修复一并堵住。已过 `./gradlew compileJava`。以下为修复前的发现存档。
- 文件：`crafting/batch/GenericBatchDelegate.java:220-222`
- 现象：重复执行循环里 `captureActualCraftingOutputs`（L235-243）只在 `!assembled.isEmpty()` 时覆盖 `pendingResult`；随后 L221 `pendingResult.copy()` 读取。若某轮 `assembleCraftingOutput` 返回空，会读到上一轮的结果而非失败。
- 为何**不是**刷物：`operationMaterials`（L213-219）每轮把同一 `materials.get(i)` 切成 `spec.count()`，逐轮字节相同；`assembleCraftingOutput` 是配方网格的纯函数 → 确定性。要么每轮都成功（N×正确产物，对应 N 份被消耗的材料，守恒），要么首轮进入前 `pendingResult` 已是非空的 `computeResult()` 模板（L160），每轮回退到同一模板（N×模板，计数仍守恒）。
- 残余风险：仅对「非确定性的模组合成配方」可能错误保留上一轮的 NBT（而非报错），属健壮性而非守恒问题。
- 修复方向：把 L221 改为读取 `captureActualCraftingOutputs` 的返回值（让该方法返回本轮 assembled，空则返回 EMPTY），循环内据此判断 `if (operationResult.isEmpty()) return false;`，消除对成员 `pendingResult` 的跨轮依赖。

### [P3] ParallelCraftGroup MaterialBroker/单线程假设缺断言（与 crafting-graph 域同类） ✅ 已修复

**修复时间**：2026-07-24

- 文件：`crafting/loadbalancer/ParallelCraftGroup.java:36-49`
- 原问题：整组状态依赖「仅服务器 tick 线程访问」的不变量，但无防御性断言
- 修复：添加显著的 Javadoc 警告，明确标注线程安全约束（与 MaterialBroker 同类修复）
- 说明：将隐性约定转换为显式文档契约

---

## 覆盖说明
- AbstractBatchDelegate.java (315)、IBatchDelegate.java、BatchConcurrencyCapabilities.java (92)：契约/能力声明，无可执行守恒逻辑；`clearMachineState` 的双退款守卫契约见 mods-storage-misc 域对 aetherworks 的 P0（[[bug_aetherworks_double_refund]]）。
- BatchCraftNetworkHandler / Craft*Packet / CraftStatus* / PreparationMessageScope：网络与进度同步，已在本域快速核对无守恒逻辑；如需深审归入 network 域。
- GenericCraftPacket.java (2383 行)：体量大，含 apotheosis 宝石切割目录 + stack-preference 扩展（未提交 diff）。本次仅确认这些为良性扩展；建议单独排期深审（当前不在 P0/P1 风险面）。
