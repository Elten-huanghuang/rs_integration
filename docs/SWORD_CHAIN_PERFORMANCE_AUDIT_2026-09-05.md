# 寰宇剑合成链性能审计（2026-09-05）

## 范围与证据边界

检查当前 1.4.2 工作区的预览、纯规划、typed 回退、状态投影、RS 快照、物料提交和机器执行。此次仅审计，不修改代码、配置或已部署 jar。

整合包：`D:\E\桌面\游戏\PCL\.minecraft\versions\Chapter of Yuusha v3.13.x 私货宇宙版`。
latest.log/debug.log 最后写入时间均为 2026-09-05 17:08:40，早于当前修复版 1.4.2 的 17:49:51 部署。下面旧日志不能作为新版实机性能结果，没有进行在线采样。

## 按优先级排列的问题

### P1：typed 回退仍同步占用服务器线程

- `src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java:4159`：预览直接调用 CraftingResolver，读取实时玩家、世界和网络。默认预算 500ms。
- `GenericCraftPacket.java:1301`、`crafting/planning/TypedPreviewAdmissionQueue.java:46`：队列限制每 tick 准入数，但执行 request.task().run()，不是可暂停的递归任务；一次请求仍可能占完整预算。
- `GenericCraftPacket.java:812` 及 1535、2426 等执行回退点使用 compatibilityResolverBudgetMs；当前源码默认值 2000ms（config/RSIntegrationConfig.java:36）。这是源码默认，不代表已核实旧游戏进程的运行配置。
- 实际旧日志 debug.log:67639-67643：2026-09-05 13:58:22.876 纯规划不完整后重试 typed，13:58:23.415 在 Server thread 上报告 timeoutMs=500。对应红羊毛配方，不是寰宇剑，证明该路径确实被使用，但不证明剑链已发生同样超时。
- 优化：有完整不可变投影的 smithing/CraftTweaker 配方继续走后台；不要仅因普通缺料就无条件重跑完整 typed 搜索。真正依赖实时状态的配方使用分阶段、可续跑的主线程任务，保存递归进度，每 tick 按共享时间预算推进。
- 限制：不能直接把 Level、ItemStack capability、RS 提取或第三方 recipe 回调扔到线程池。500ms 超时是协作检查，不会强行中断单个很慢的第三方方法。

### P1：进入后台之前仍有全图锻造状态展开

- `crafting/planning/PureDemandTreeInspector.java:69` 先 bindSmithingStates，再进行 maxNodes 限制的 Walker。
- `GenericCraftPacket.java:1044`、3946 在路由/预览阶段调用 inspector，故不能认为所有纯图工作已经移出主线程。
- `crafting/planning/ImmutableRecipeGraphProjector.java:166`：每次遍历 recipesById 建 smithing 升级表，以所有有 NBT 库存和有 NBT 配方产物为种子，展开所有能到达的锻造状态；不局限于当前剑链。
- visited 集合防止同状态循环，但不同词缀、附魔、RepairCost 都是不同合法状态；状态多时仍有明显分配和扫描风险。maxNodes 不限制这段前处理。
- 优化：在 RecipeCatalog 发布时建立不可变 base-item 升级索引；按当前目标的祖先配方范围惰性展开；在同一请求内复用一次展开后的图；将完整覆盖检查放在后台。不能通过删除 RepairCost/itemModifier 或把库存合并成 tagless 来提速。

### P2：重复绑定、全输出扫描和 SNBT 重解析

- `planning/AsyncPurePlanningService.java:68` 调用 bindAvailability，其中调用 bindSmithingStates；随后 `planning/PureRecipePlanner.java:64` 又调用一次 bindSmithingStates。
- `planning/AsyncMaxCraftablePlanningService.java:43` 绑定后，每个最大合成数探测再次进入上述 resolve；会重复做状态图准备。
- `planning/ImmutableRecipeGraphProjector.java:298`：除了 EXACT 且无 NBT 的快捷分支，candidates 遍历整个 recipesByOutput，然后按 itemId 筛选。ANY/PARTIAL 和有 NBT EXACT 都可能走这里。
- 同文件 245、259：匹配过程中重复 TagParser.parseTag；全库存/多候选/深链会放大此开销。
- 旧日志 debug.log:93125 记录 11588 个 pure recipes，说明不能把这里当成只有几把剑的小图；实际耗时还取决于库存状态数、候选数和调用次数。
- 优化：输出按 registry ID 建不可变索引；请求级缓存解析结果和三种匹配模式的比较结果；请求级 prepared graph 复用。保持 EXACT/PARTIAL/ANY 区别、非零 Damage 拒绝和库存一对一计数。

### P2：纯规划超时不覆盖全部准备工作

- `AsyncPurePlanningService.java:68` 图绑定在 searchStarted/deadline 创建之前。
- `AsyncMaxCraftablePlanningService.java:43` 同样在绑定之后创建总搜索 deadline。
- `PureRecipePlanner.java:1004` SeededReachability 构建全图依赖结构，其 1048 附近明确只检查取消、不检查搜索 deadline。
- 后台工作本身不等于主线程阻塞，但重复准备会增加 CPU、内存分配和排队；不能把配置中的 1500ms 解释为从入队到返回的绝对上限。
- 优化：区分请求总预算、图准备预算和搜索预算；在准备阶段设置取消/工作量边界。预算耗尽返回 UNKNOWN/TIME_LIMIT，不伪报缺料，也不立刻用更贵的同步递归重试。增加分阶段耗时指标，避免把前处理时间漏记。

### P2：typed 锻造继承路径重复扫描整个库存

- `crafting/CandidateEngine.java:260` 的 findStockedSmithingOutput 每层扫描 available，逐项 toStack，然后检查 base、assemble。
- 已有 64 个配方访问上限和超时检查，不是没有循环保护；但同一份大 RS 库存会在不同层多次扫描和构造 ItemStack。
- 优化：在单次请求中按 item registry ID 分桶，保留桶内完整 NBT；先按 base ingredient 的候选 item 定位，再做真实语义检查。缓存必须包含请求快照和实际 NBT，不缓存跨库存变化的旧结果。

### P2：执行预算是外层软预算，物料事务并未分片

- `crafting/AsyncCraftManager.java:183`：已有全局时间预算和原版操作数限制；但时间检查位于 chain.tick 之间，无法中断单次慢 chain.tick/机器回调。
- `crafting/AsyncCraftChain.java:887` 附近还会在一次链 tick 内推进 graphExecutor。
- `crafting/ExtractionLedger.java:641`：一次 commit 内完整 preCheck、循环提取、确认，失败则回滚。大型原料表/慢外部存储可能产生单次尖峰。
- `crafting/MaterialSources.java:62`：复制 RS storage cache 全列表并生成 StackKey；已有同 tick、同网络缓存，不是每次都重新扫描，但首次快照仍同步工作。
- 优化：先按物理 StackKey 合并等价提取请求、复用快照/事务内校验结果、限制一次调度物料条目数。若跨 tick 提交，必须设计预留锁、库存变更重验、取消和完整回滚状态机，不能简单在循环中途 return。
- 建议在快照、事务 preCheck/extract/rollback、机器 dispatch/observe 周围分别记录耗时、物料条目数、实际配方 ID。第三方机器原生 tick 是否昂贵仍需实机采样，不能仅由配方名称判断。

## 不应误判的日志

- Nearby scan 的 949/570/692/772ms 是任务总历时：`network/binding/NearbyBindingService.java:105` 已跨 tick 分片，303 的日志用 startedNanos 计算总时间。当前 common.toml:21 的 nearbyBindingTickBudgetMicros=1000。不能据此声称单 tick 阻塞 949ms；单次 job.step 仍属于软预算边界。
- RecipeCatalog 的 3297/3805ms 日志在线程 RSI-RecipeCatalog，不是 Server thread。不能直接当成服务器停顿时长。
- debug.log:73332，14:48:25.856 的 Running 45119ms；73657，15:01:45.909 的 Running 22971ms，表示服务器进度落后，不是已证明单次合成函数执行 45/23 秒。邻近日志为大量死亡骑士死亡事件；只能作为实体/战斗负载排查线索，不能直接定罪任何 mod。
- 本次没有拿到上述时刻能够定位具体阻塞函数的 Server thread 采样栈。不关闭 watchdog，不把空白行解释成配方 ID。

## 建议落地顺序

1. 先做低风险性能补丁：item ID 输出索引、库存分桶、请求级 SNBT 缓存、prepared graph 单次绑定，保留现有全部 NBT/配方树回归测试。
2. 将路由 inspector 的完整状态展开移至不可变后台任务；准备和搜索分别计时，预算耗尽不转同步暴力搜索。
3. 将必须访问世界的 typed 搜索改为主线程可续跑状态机，接入全局预算，而非只限制每 tick 请求个数。
4. 采样证实提交/机器执行为热点后，再做事务分片和机器调度细化，不为追求吞吐破坏物品原子性。
5. 不建议靠提高 craftingResolveTimeoutMs/typed timeout、增大每 tick 操作数来解决卡顿；降低超时仅是临时止损，可能降低复杂配方成功率。

## 新版实机验收方法

使用项目已有命令 `/rsi_debug perf`，分别在基线、打开剑链预览后、实际合成后取快照；`/rsi_debug dump chain` 可查看当前执行阶段。命令定义见 command/DebugCommand.java:44、71。

重点对比 PerformanceMonitor 的 phaseSnapshot、demandTree、phaseTyped、phasePure、planningPool、delegateObserve、delegateTypes、syncFallback；耗时输出单位是 us，包含累计/平均/最大值，不要把启动期间旧最大值当成本次新增样本。计数增长和分阶段记录需要结合时间窗口判断。

分别测：带原词缀木剑、带原词缀石剑、同类剑大量不同 NBT 的大库存、多玩家同时预览。先做不合成的同场景基线，再预览，再实际执行，区分实体/机器自身负载和 RSI 请求负载。本地 mods 未找到文件名含 spark 的 jar；本次不擅自安装性能 mod或启动游戏。

本次没有改动运行代码，因此没有新增构建或重新部署；上一轮 1342 项测试结果不是性能基准。
