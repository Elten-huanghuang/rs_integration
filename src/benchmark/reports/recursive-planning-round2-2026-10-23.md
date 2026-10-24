**递归合成规划第二轮优化 · 2026-10-23**

已完成上一轮建议中的前四项：候选评分复用、库存计数减少分配、派生图按需复制、兼容解析延迟创建展示与诊断数据。继续使用现有工作线程数量和搜索范围。强制选择重复投影、共享待办任务尾部、主线程兼容求解跨 tick 分片不在本轮修改中。

本轮基线是已包含第一轮优化的工作区，而不是最初的 Git 提交。因此以下增益属于第一轮之后的继续优化。

**实现及语义边界**

- [PureRecipePlanner.java:842](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/PureRecipePlanner.java:842)：一次排序内复用候选库存覆盖率。依赖深度、备选数和配方 ID 文本在首次比较需要时计算，后续复用；排序结束即释放该批评分。保留原有排序字段与优先级，不在不同库存状态间复用评分，也不为了预计算所有依赖而扩大遍历。
- [PureRecipePlanner.java:1101](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/PureRecipePlanner.java:1101)：库存计数直接遍历匹配 itemId 的库存桶，省去中间列表和流。多备选输入的 itemId 去重结果按不可变备选列表身份缓存，每次搜索最多保留 8,192 个计费单元，计费包含键列表、物品 ID 列表的元素数量及条目；超过额度仍完整计算。该额度是索引规模约束，不是整个请求的字节上限。库存数量、新产生的 NBT 变体、匹配语义及饱和加法保持实时处理。
- [CraftPureExecutionPlanner.java:147](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/batch/CraftPureExecutionPlanner.java:147)：插入合成根节点时共享已有不可变候选列表，只复制目标桶。保留原外层映射的插入和构造方式，避免改变同 ID 多输出的代表配方选择。外层索引仍需构建，未宣称整个操作变为常数时间。
- [GenericCraftPacket.java:814](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java:814)：绑定过滤首次删除候选时才复制桶，首次发现图变化时才创建结果映射；全部通过时直接复用原图。仍逐个执行运行条件和机器绑定检查，机器检查仍只发生在运行条件通过之后，且保留两类失败的诊断区别。
- [CraftingResolver.java:899](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/CraftingResolver.java:899)：通过循环、自输入和反向转换检查后才构造备选列表。`ResolutionContext` 暴露诊断开启状态，调用处先判断再拼接字符串；昂贵的日志参数先检查详细日志开关。`CandidateEngine` 的日志展示集合也改为需要输出日志时才构造。备选配方内容、顺序及正式诊断语义保持不变。

上述构图和兼容解析改动直接减少主线程分配；纯规划改动主要降低 worker 的计算和分配量。没有把世界对象移到后台线程，也没有添加常驻的全局无限缓存。实际服务器竞争、GC 停顿和 MSPT/TPS 仍需整服验证。

**新增多候选探针**

正式探针新增一个目标有多个等价生产者的场景：每个配方有 4 组输入，每组包含同一 itemId 的 32 个 NBT 备选，库存共 128 种。生产者顺序固定种子打乱，前后都选择 `producer_0000`，成功计划只有 1 步，均展开 7 个搜索状态、0 次回溯。这里的 16/128/512 是单个目标的候选数量，不是配方库总量。

每场景预热 4 次、测量 7 次；前后在独立 JVM 中运行，堆参数为 `-Xms512m -Xmx2g`。耗时为中位数，分配量为当前线程每次操作的平均分配，不是保留堆大小或 GC 停顿。

| 单目标候选数 | 修改前耗时 | 修改后耗时 | 修改前分配/次 | 修改后分配/次 |
|---:|---:|---:|---:|---:|
| 16 | 38.68ms | 13.38ms | 30.85MiB | 5.77MiB |
| 128 | 526.44ms | 45.97ms | 451.87MiB | 23.55MiB |
| 512 | 2,768.38ms | 178.81ms | 2,439.17MiB | 84.45MiB |

512 候选场景的分配量下降约 96.5%。这是人工压力样本，不能据此推导整服倍率；它说明搜索状态很少时，排序及反复库存统计仍可能是主要开销。

原始数据：[本轮修改前](D:/sd/rs-integration/src/benchmark/reports/data/recursive-planning-round2-before.csv)、[本轮修改后](D:/sd/rs-integration/src/benchmark/reports/data/recursive-planning-round2-after.csv)。

**短链交替对照与测量限制**

4 次预热的整套探针中，短链耗时未一致改善，未改动的输出查询对照也出现明显波动。完整 CSV 保留这些结果，没有只保留改善的项目。为继续检查短链是否回退，把修改前纯规划器改名编译到临时目录，与当前实现放在同一个 JVM，交替先后顺序运行；每个库存规模预热 40 对，测量 31 对，比较完整结果文本。

| 64 步链库存种类 | 修改前中位耗时 | 修改后中位耗时 | 修改前分配/次 | 修改后分配/次 |
|---:|---:|---:|---:|---:|
| 100 | 1.883ms | 1.342ms | 3.679MiB | 1.690MiB |
| 1,000 | 2.094ms | 1.422ms | 3.967MiB | 1.977MiB |
| 10,000 | 3.841ms | 3.362ms | 6.874MiB | 4.886MiB |

此对照中三个规模均减少耗时和分配，前后结果一致。它仍是单 JVM 机制诊断，不是多 fork JMH、真实服务器基准或可靠 p95/p99 估计。不能把交替对照数字与独立 JVM 的数字混成同一套测量。

交替对照数据：[CSV](D:/sd/rs-integration/src/benchmark/reports/data/recursive-planning-round2-interleaved.csv)。额外一次性对照源码及旧规划器备份已清理；正式探针和上述三个 CSV 保留在 `src/benchmark`。

**验证**

- 1,208 项 crafting 相关测试全部通过，0 失败、0 错误、0 跳过，包括原有 200 个随机小图与独立 BFS 参照比较。
- 新增测试验证：前一配方耗尽库存后重新评分；同 itemId 多个 NBT 备选不会重复计算库存；后续需求可看到新生产的 NBT 变体；构图共享不可变列表且不修改原图；过滤顺序与机器阻塞原因保持正确。
- 额外 400 组固定种子的有环、多生产者、多根需求随机图，修改前后完整结果一致，包含计划步骤、剩余库存、状态和搜索计数。日志为 `build/planning-audit/round2-interleaved.log`。
- 最后保留构图外层映射的原插入方式后，55 项构图与入口相关测试再次通过。这 55 项是上面 1,208 项的子集，不能相加作为独立测试数量。
- `git diff --check` 通过。没有运行所有非 crafting 测试，也没有部署到真实服务器。

复现正式回归和探针：

```powershell
.\gradlew.bat --offline test --tests 'com.huanghuang.rsintegration.crafting.*' --console=plain
.\gradlew.bat --offline planningPerformanceProbe --console=plain
```

本轮日志：`build/planning-audit/round2-validation.log`、`round2-probe-final.log`、`round2-final-graph-tests.log`。探针运行后写入 `build/benchmark/planning/results.csv`。代码及报告仍在工作区，未提交 Git。
