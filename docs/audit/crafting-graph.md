# crafting/graph 域静态审计 — DAG 调度 / 并发执行 / 材料仲裁

审计范围：`crafting/graph/` 28 个文件。重点通读：MaterialBroker、ConcurrentNodeExecutor、DagScheduler、CraftPlanValidator、GraphConcurrencyPolicy、NodeAdmissionCoordinator、MachineLeaseRegistry、CaptureLeaseRegistry、OperationBudget、NodeOutputAccumulator、TerminalGraphComposer。

一句话总评：**本域是全项目并发/资源守恒设计最成熟的部分，本轮未发现有确凿代码证据的 P0/P1。** 线程模型清晰（executor/scheduler/broker 全在服务器 tick 线程单线程访问，物理机器才并发在世界里跑），跨线程共享的 MachineLeaseRegistry 全 `synchronized` 且带 generation+owner 双重守卫。下列仅记录若干 P2/P3 健壮性观察点与需接手 AI 留意的设计约束。

## 线程模型（关键，供接手 AI 参考）
- `ConcurrentNodeExecutor`（:10 javadoc "Server-thread tick loop"）、`DagScheduler`（:14 "server-thread executor"）、`MaterialBroker`（:14 "Server-thread material arbiter"）**均只在服务器 tick 线程访问**，故 MaterialBroker/DagScheduler 用普通 `HashMap`/`LinkedHashMap` 无同步是**安全的**，不是 bug。所谓"并发"指多个物理机器同时在世界里执行，executor 每 tick 用 `observe()` 轮询，不是多线程改这些结构。
- 反例：`MachineLeaseRegistry`（:38 起全部 `synchronized`）与 `CaptureLeaseRegistry` 确实跨线程，已正确加锁。接手 AI **不要**给 MaterialBroker/DagScheduler 加锁（无必要），也**不要**把它们的调用挪到 worker 线程（会破坏单线程假设）。

## 已验证正确的守卫（非 bug，记录以免误改）
- **资源守恒**：MaterialBroker 的 available/reserved/committed/settled/delivered 五态转移配对守恒（reserve↔release、commit↔refund、settle 终态）。`refundCommittedProducerFragments`（:176）只退未达机器的 producer 片段，且末尾 `if (!remaining.isEmpty()) throw`（:198）守住"退款超过 committed"的越界。
- **exclusivity 门控**：`ConcurrentNodeExecutor.dispatchAvailable` 三处守卫——已有 exclusive 在跑则完全不派发（:285）、exclusive 节点只在空场启动（:297）、刚启动的 exclusive 立即停止继续派发（:329）。对照历史坑「能力门控只 warn 不拦截」，此处是**结构性强制**，已修复态。
- **lease 原子性**：`tryAcquireAll`（:46）先全查再全占，任一被占则返回 null 不留部分租约；`release`（:71）用 `generation != lease.generation || !owner.equals` 守卫，防旧租约释放踩掉新租约。
- **epoch 隔离**：succeed/fail 用 `result.epoch == scheduler.epoch()` 区分 stale 完成（ConcurrentNodeExecutor:218），stopping 期间走 `cancelRunningDuringStop`/`failRunningDuringStop`，不解锁 dependents。

---

### [P3] MaterialBroker 类无线程安全，仅靠"服务器线程单线程访问"的隐式约定，无 assert 护栏 ✅ 已修复

**修复时间**：2026-07-24

- 文件: crafting/graph/MaterialBroker.java:12-24
- 原问题: `lots`/`reservations` 为普通 HashMap，全类无同步，安全性完全依赖隐式约定
- 修复: 添加显著的 Javadoc 警告，明确标注线程安全约束：
  ```java
  /**
   * <b>THREAD SAFETY:</b> This class is <b>NOT</b> thread-safe.
   * All methods must be called from the server tick thread only.
   * The internal HashMaps are not synchronized; calling from worker threads
   * or async callbacks will cause data races leading to item duplication or loss.
   *
   * <p>Do not convert to ConcurrentHashMap without also synchronizing
   * multi-step operations (reserve + commit is not atomic).
   */
  ```
- 说明: 将隐性约定转换为显式文档契约，降低未来维护风险

### [P3][已修复] processTick 中 publish 抛异常被降级为 FAILED，但异常本身被吞无日志
> ✅ **已修复**：发布异常现在记录节点 ID 和完整堆栈后再降级为 FAILED。
- 文件: crafting/graph/ConcurrentNodeExecutor.java:199-201
- 维度: Null 与异常
- 现象: `publications.publish` 抛 RuntimeException 时 `observation = Observation.FAILED`（:200），异常对象未记录。同样 `completions.complete` 异常降级 FAILED（:214-215）、`failures.failed` 异常直接 `catch { }`（:232-233）。
- 风险: 发布/完成阶段的真实 bug（如 NPE）会被静默转成"节点失败"，abort 路径虽能守恒（材料退回），但排障时看不到根因堆栈。P3：不影响守恒，仅诊断可见性。
- 修复方向: 在降级处加 `RSIntegrationMod.LOGGER.debug/warn(..., exception)`。

---

## 结论
crafting-graph 无需修复项（P0/P1 空）。两处 P3 为可选加固，接手 AI 可低优先处理或跳过。**重点提醒：本域的无锁结构是刻意的单线程设计，勿"顺手加锁"。**
