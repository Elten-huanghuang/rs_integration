**递归合成规划性能审查与优化记录 · 2026-10-23**

已直接修改代码并验证：冷依赖裁剪不再扫描全部输出桶；失败缓存键不再逐状态复制完整库存；大型派生图在内存额度内使用数组索引，消除原有 65,536 输出变体附近的重复扫描退化。相关测试和诊断基准已经通过。

这次改动降低了规划 CPU 开销及分配量，但没有把所有特殊配方兼容求解改成可跨 tick 恢复的任务。真实服务器 MSPT/TPS 稳定性仍需按后文方案验证。

后续已完成候选评分、库存计数、派生图复制及兼容诊断分配四项优化，见[第二轮实现与验证](D:/sd/rs-integration/src/benchmark/reports/recursive-planning-round2-2026-10-23.md)。下方第一轮数据与补充审查保留为历史记录。

**范围与测量条件**

- 用户场景：配方库总量 5万–10万，主要是实际合成规划慢。
- 修改前基准提交：`f630648b9b86e5a7b5ee912c525c148e47d9c3d7`；修改后数据对应本次工作区代码。
- i7-12700H、Windows、Java 17.0.20.1；探针 JVM 为 `-Xms512m -Xmx2g`。
- 直接调用生产类，每场景预热 4 次、测量 7 次。图构建、代际索引发布、派生图生成不计入对应查询测量。报告使用耗时中位数。
- 分配量来自当前线程的 `ThreadMXBean.getThreadAllocatedBytes`，不是保留堆大小，也不是整服 GC 停顿。
- 修改前、修改后分别在独立 JVM 运行。样本较少，执行顺序、JIT、GC、系统负载会影响绝对时间；这是机制诊断探针，不是正式多 fork JMH 或真实整合包服务器基准。
- 数据完整保留，包括没有变快的小规模场景；不能把单个微基准的倍率理解为整服性能倍率。

**同场景前后对比**

| 场景 | 修改前中位耗时 | 修改后中位耗时 | 修改前分配/次 | 修改后分配/次 |
|---|---:|---:|---:|---:|
| 5万配方，冷裁剪为 17 条相关配方 | 12.55ms | 0.166ms | 16.68MiB | 0.022MiB |
| 10万配方，冷裁剪为 17 条相关配方 | 20.08ms | 0.073ms | 33.59MiB | 0.021MiB |
| 64 步链，100 种库存 | 5.60ms | 7.42ms | 4.20MiB | 3.87MiB |
| 64 步链，1,000 种库存 | 8.66ms | 5.67ms | 7.42MiB | 4.11MiB |
| 64 步链，10,000 种库存 | 55.41ms | 8.26ms | 41.35MiB | 6.90MiB |
| 同上，关闭失败缓存 | 54.10ms | 5.06ms | 41.35MiB | 6.64MiB |
| 5万输出变体，派生图查询 100 次 | 10.43ms | 1.41ms | 9.98MiB | 0.47MiB |
| 65,536 输出变体，派生图查询 100 次 | 23.59ms | 2.50ms | 12.76MiB | 0.58MiB |
| 65,537 输出变体，派生图查询 100 次 | 100.18ms | 3.83ms | 0.025MiB | 0.65MiB |
| 10万输出变体，派生图查询 100 次 | 226.48ms | 3.21ms | 0.025MiB | 0.91MiB |

最后两行体现了取舍：原实现通过不建索引减少分配，却反复扫描全图。现在在明确的数组内存额度内，一次建索引换取后续局部查询。10万输出变体场景的扫描量从 10,000,000 项减少为 100,000 项，索引构建次数为 1。

64 步链的前后结果均为 SUCCESS、130 个搜索状态、0 次回溯、0 次记忆命中。生产代码仍然接收并保留完整库存，没有靠丢弃无关库存或剪掉可行配方取得这个结果。小库存场景耗时没有一致提升，不应从这组短样本断言它更快；大库存场景的分配下降和扫描量变化则可直接解释。

原始数据：[修改前](D:/sd/rs-integration/src/benchmark/reports/data/recursive-planning-before.csv)、[修改后](D:/sd/rs-integration/src/benchmark/reports/data/recursive-planning-after.csv)。

**已落地：按命中的配方构建依赖子图**

[ImmutableRecipeGraphProjector.java:357](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/ImmutableRecipeGraphProjector.java:357) 原先完成倒查后，仍对全图的每个输出桶创建 stream，再按 visitedRecipeIds 筛选。小子图的冷请求因此承担了与总配方数相关的扫描和分配成本；实际合成入口会在后台任务提交之前调用它。

现在复用生产者索引，只读取命中配方的输出桶。新增 `RecipeProducerIndex` 为同一配方 ID 的附加输出维护稀疏索引，保留 NBT/动态输出变体及桶内候选顺序。原图的 canonical 配方映射也被保留，避免裁剪顺序改变代表配方。

同步修正了旧版“输出桶数量相同就直接返回原图”的条件：即使没有少输出桶，也可能需要删除桶内的无关配方。只有配方 ID 已全部到达时才复用整图。

未发布图的倒查及锻造状态分析进入同一个请求缓存作用域，避免每个材料重建生产者索引。缓存命中保持轻量；依赖遍历和命中路径增加取消检查。

代际依赖缓存仍保留最多 32 个目标，不新增跨请求的无限缓存。主线程绑定遍历仍然存在，不能把这项优化理解为整个准备阶段已全部异步化。

**已落地：失败键保存库存差量**

[PureRecipePlanner.java:420](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/PureRecipePlanner.java:420) 原先每个状态都复制完整库存，成功路径、缓存容量为零的路径也如此。

现在每次搜索固定一份初始库存；`setStock` 维护当前库存相对初始库存的变化。失败键仅复制差量、待办需求和祖先集合。初始材料耗尽时显式记录零；恢复初始数量时移除差量；新增材料在回滚至零时也移除差量。

在同一次搜索内，初始库存固定，所以差量和完整库存表达相同状态。这不是近似 hash，也没有省略 NBT、催化剂或初始库存信息。缓存容量为零时不再创建失败键，也不维护这份差量。现有库存扣减、回滚及最终 remaining 结果继续使用完整库存。

若 S 为搜索状态数、I 为初始库存种类数，原库存键复制成本约为 O(S×I)。现在这部分随各状态实际变化的材料种类数增长；初始快照和最终结果仍然有 O(I) 成本。失败键仍保存待办和祖先状态，搜索触及大量材料时仍可能消耗较多内存，尚未实施完整的按字节加权失败缓存。

**已落地：大图使用有上限的数组索引**

[PlanningLookupCache.java:305](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/PlanningLookupCache.java:305) 原先要求整图输出索引能放入剩余变体额度，否则每次查询扫描所有输出。65,536 是旧缓存的输出变体额度，不能与配方总数直接画等号；已发布根图本来就能复用代际索引。

现在达到 4,096 个输出变体的大图优先使用 `CompactOutputIndex`，由输出引用数组、链索引数组和哈希桶数组构成。倒序挂链保留原图输出顺序，哈希冲突通过 itemId 相等检查处理。

默认数组索引的总预算为每请求 2MiB，多张派生图共用；预算估算按 8 字节引用保守计费，并预留对象头开销。图数量上限仍与普通输出索引共用。关闭缓存时数组预算也为零。超出额度时保留完整候选并允许退回扫描，没有通过截断候选宣称不可合成。这个 2MiB 是新增数组索引额度，不是整个请求的总内存上限。

大图初次查询仍需 O(N) 建索引，尚未实现所有派生图与根图的共享增量视图。极端单物品大量 NBT 变体仍需要逐变体匹配，后续可继续优化这类桶。

**已补充：启发式评分保护**

[PureRecipePlanner.java:864](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/PureRecipePlanner.java:864) 的依赖深度评分现在检查搜索截止时间，并限制评分递归深度。无法继续估计时使用保守的大成本作为排序提示，不据此删除候选。

这项保护不等于所有预处理、第三方回调和超时后的诊断工作都已经受到统一时间预算约束。

**仍需处理：主线程兼容规划与全局预算**

实际合成的 [GenericCraftPacket.java:1715](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java:1715)、[1872 行](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java:1872) 等兼容路径，仍可能在主线程执行 typed resolver。默认执行兼容预算为 2,000ms，特殊预览预算为 500ms；并非每次请求都耗时这么久，但达到这个量级时会造成 tick 尖峰。

`TypedPreviewAdmissionQueue` 限制的是每 tick 启动的请求数，不能中途暂停单个请求。此次没有通过简单缩短超时来制造更多规划失败，也没有把非线程安全的世界对象移到后台。

后续应把兼容解析拆成显式状态栈，记录需求、候选游标、库存撤销点和祖先状态，允许在安全边界保存并恢复。主线程按服务器全局时间及操作额度推进；可先以每 tick 总计 1–2ms 为压测目标，再按实际余量调整。单次不可拆分的第三方回调必须单独计时；进入前检查预算，无法安全预捕获时不能声称硬实时保证。取消、下线和快照失效必须能释放暂停任务及其资源。

其他预算欠缺仍包括：完整依赖闭包和强制选择投影的截止时间覆盖、TIME_LIMIT 后诊断的实际时间额度、缓存保留字节、回调提交到主线程的总量，以及 1/2/4 worker 并发时的 CPU 与 GC 竞争。当前默认队列/状态/条目上限已有作用，但不等价于字节或 tick 时间上限。

**搜索与剪枝建议**

保留内存受控的有界 DFS。配方具有“候选配方任选、配方输入同时满足”的 AND/OR 关系，库存又被各分支共享；倒查有助于限制候选，不能完全消除数量竞争。

后续依次考虑：

1. 按需扩大候选和标签变体范围，让已有库存、强制选择及当前缺口先发挥作用，避免准备阶段遍历所有替代路线。
2. 对允许交换的普通输入，优先处理候选少、库存紧张的需求。催化剂可能由前置步骤返还，机器输入槽有语义边界，不能任意重排。
3. 对已证明无生产者且库存不足的叶子、确定 NBT/绑定不匹配的候选，以及无种子、无净增益的转换环做硬剪枝。可达性未知只能降低优先级，不能直接判缺料。
4. 缓存静态邻接结构和廉价候选评分，减少比较器中的重复遍历。循环分量可辅助组织搜索，但自增、催化剂和返还物的合法循环必须保留。
5. 如需最少步骤或最少成本，应先明确优化目标，再做有界分支定界或渐进优化。当前保证的是预算内寻找可行计划，并非全局最优。

**验证结果与上线验收**

本次最终运行 **1,202 项 crafting 相关测试，全部通过，0 失败、0 错误、0 跳过**，并通过 `git diff --check`。没有运行整库所有非 crafting 测试。

新增/扩展的验证包括：

- 10万输出变体下重复查询只建一次索引，候选与原始参照一致。
- 数组索引哈希碰撞、原始候选顺序、不可变返回值、取消与共享内存额度。
- 裁剪保留同 ID 的不同 NBT、不同输出及 canonical 配方；删除同桶无关配方。
- 5万无关配方的稀疏依赖、目标缓存复用、命中时取消。
- 200 个随机小图与独立广度优先参照求解器对照；额外加入无关 NBT 库存，在失败缓存关闭、容量 1 和正常容量下核对可行性、剩余库存和成功步骤。
- 现有 NBT、催化剂、自增、循环、异步队列、取消、执行与材料守恒回归。

真实服务器还应使用相同整合包、库存与请求集合，分别测冷/热目标、深链、宽标签、失败回溯、多人同时请求和频繁取消。至少记录规划耗时 p50/p95/p99、每请求分配量、整服 MSPT p95/p99、超 tick 预算次数、TPS、GC 停顿分布和分配速率。当前本机样本不足以给出可信 p95/p99，也没有线上 TPS 提升结论。

`/rsi_debug perf` 可帮助归因，但其 `avg/max` 来自 [AsyncCraftManager.java:231](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/AsyncCraftManager.java:231) 的局部管理器耗时，不是整服 MSPT。

**复现**

在仓库根目录运行：

```powershell
.\gradlew.bat --offline test --tests 'com.huanghuang.rsintegration.crafting.*' --console=plain
.\gradlew.bat --offline planningPerformanceProbe --console=plain
```

探针为 [PlanningAuditProbe.java](D:/sd/rs-integration/src/benchmark/java/com/huanghuang/rsintegration/crafting/planning/PlanningAuditProbe.java)，输出至 `build/benchmark/planning/results.csv`。它与磁盘基准共用 `benchmark` 源集，不进入生产模组；通过 manifest 承载 classpath，避免 Windows 命令长度限制。首次构建若缺少本地依赖，需先使 Gradle 依赖可用。

磁盘基准与规划探针现已统一使用 `benchmark` 源集。三份报告、前后 CSV 与探针源码分别保存在 `src/benchmark/reports` 和 `src/benchmark/java`，不会被 `gradlew clean` 清除。

**补充审查：继续加速且控制服务器开销的具体位置**

以下记录第二轮修改之前的静态审查：其中第 1–4 项随后已实施，第 5–6 项尚未实施，验证见第二轮报告。优先减少重复计算和临时对象，保持线程数、候选范围及执行校验。不能仅凭后台执行或局部更快就承诺服务器零影响。本节源码行号对应审查当时的版本。

| 优先级 | 优化项 | 主要受益场景 | 改造范围 |
|---|---|---|---|
| 先做 | 候选排序的成本在一次排序内只算一次 | 多生产者、宽标签、回溯多 | 小至中 |
| 先做 | 库存计数直接累加，去掉临时匹配列表 | 高频库存匹配、NBT 变体多 | 小至中 |
| 先做 | 派生图只复制发生变化的候选桶 | 机器合成准备、依赖子图大 | 小 |
| 先做 | 兼容解析延迟构造备选列表及诊断字符串 | 循环候选多、诊断关闭 | 小 |
| 按场景做 | 消除重复强制投影，按偏好键定位输出 | 用户指定配方、强制选择多 | 小至中 |
| 第二阶段 | 待办任务共享不可变尾部 | 深链、宽分支、失败缓存分配高 | 中 |

1. 候选评分预计算。

   [PureRecipePlanner.java:836](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/PureRecipePlanner.java:836) 把 `inputStockCoverage`、`dependencyDepth` 和结构成本放在排序比较器里；依赖成本调用还创建 `HashSet`。`producerDepthCache` 已经缓存部分材料深度，但没有消除候选输入遍历、库存覆盖统计等重复开销。可以在一次排序内保存候选评分，比较时只读取结果，并保留原有优先级及平局顺序。库存变化后必须重新计算库存相关分数；不能把当前库存分数放进跨请求缓存。存在循环的深度估计还需验证评分求值顺序不会改变最终选择。

   同方法的 822 行使用 `ordered.contains(candidate)` 补入候选；宽候选集还可配合一次性的成员集合减少重复线性查找，保持原来的相等语义及列表顺序。旧 `CandidateEngine` 已经预计算主要评分，不应再把它描述为完全没有评分缓存。

2. 高频库存计数减少中间对象。

   [PureRecipePlanner.java:1065](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/PureRecipePlanner.java:1065) 的 `stockAcross` 每次把 `matchingStock` 收集成列表再求和；匹配流又重复执行 itemId 去重。可直接遍历相关库存桶累加，并为稳定的输入描述保存去重后的 itemId 集合，避免每次构造列表和流管线。仍然读取当前库存数量，保留饱和加法、NBT 匹配及取消检查。

   已有匹配结果缓存和 itemId 库存索引，不需要再引入全库存扫描。新产生的 NBT 变体必须能加入候选，不能把初始匹配列表永久冻结；同一 itemId 的多个备选也不能重复计数。

3. 减少主线程派生图复制。

   [CraftPureExecutionPlanner.java:128](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/batch/CraftPureExecutionPlanner.java:128) 为插入一个合成根节点，先对依赖子图的每个候选列表执行 `new ArrayList<>(value)`，随后 `ImmutableRecipeGraph` 再冻结列表。可共享未修改的不可变列表，只复制根节点对应的桶；外层索引构造仍可能需要线性工作，不能宣称整段变成常数时间。

   [GenericCraftPacket.java:814](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java:814) 的绑定过滤也会先为所有桶创建 `runtimeCandidates`、`usable` 等容器，最后才在未变化时返回原图。可改为出现实际删除时才复制桶、延迟构造结果映射。动态可用性及机器绑定检查仍须执行，继续区分运行条件不满足与机器未绑定；不能用过期缓存跳过这些校验。上述两项直接减少提交后台任务之前的主线程分配。

4. 兼容解析先检查，再创建展示数据。

   [CraftingResolver.java:859](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/CraftingResolver.java:859) 对每个候选先遍历全部可选配方构造 `altIds`、`altModTypes`，之后才检查循环、自输入和反向转换。把纯展示列表的构造移到这些检查通过之后，可消除被立即跳过候选的分配；备选配方内容和顺序必须保留。

   [ResolutionContext.java:356](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/ResolutionContext.java:356) 的 `diag(String)` 在内部检查诊断是否开启，但调用方的字符串拼接已经完成。类似 `describeFirstItem` 的日志参数也会在进入日志方法之前求值。应在调用点检查对应开关或采用惰性消息；热路径要避免为了省字符串又引入大量捕获 lambda。该项不改变搜索语义，收益取决于候选尝试次数及诊断配置。

5. 强制选择的重复投影及偏好键计算。

   [AsyncPurePlanningService.java:113](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/AsyncPurePlanningService.java:113) 已投影强制选择，随后把结果传入 `computeInScope`，但 164 行又调用一次 `ForcedRecipeGraphProjector.apply`。非空强制选择的路由请求因此可能重复复制同一范围的图。可显式传递已完成投影的准备结果，并保留投影失败时的兼容回退。

   [ForcedRecipeGraphProjector.java:33](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/ForcedRecipeGraphProjector.java:33) 为每个偏好遍历输出；47 行还复制全部输出键。NBT 输出的偏好键包含字符串编码和 UUID 计算。可在一次请求内为输出计算一次偏好键并分组，且 `forcedRecipeFor` 可计算一次精确键后直接查询映射。保持旧偏好忽略规则、同 ID 变体、canonical 配方以及 UI 的完整备选集合。强制选择为空时已有快速返回，因此不把这项收益推广到所有请求。

6. 减少搜索待办任务的反复复制。

   [PureRecipePlanner.java:711](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/PureRecipePlanner.java:711) 的 `recipeBranch` 把整个 `rest` 复制到新分支，再转为不可变列表；420 行的失败键还会快照待办任务和祖先集合。这部分在库存差量优化之后仍然存在。

   可采用共享不可变尾部的任务表示，让分支只新增自身输入及完成标记，失败缓存引用精确快照。需要同步处理相等比较、哈希与回滚，不得用可能碰撞的指纹直接认定两个搜索状态相同，也不能让可变栈进入缓存键。该项改造比前五项大，但并不要求把整套兼容解析改成跨 tick 状态机。

另外，[PureRecipePlanner.java:150](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/PureRecipePlanner.java:150) 在搜索超时后仍可能生成部分计划，`DiagnosticBudget` 只有操作次数上限。可以给诊断增加独立、较小的实际时间额度，并在耗尽时保留已有状态。这主要改善超时请求的尾延迟和 worker 占用，不是成功搜索加速；详细缺料展示可能减少，需明确表示未完成，不能当成确定不可合成。

当时建议先做候选评分、库存计数、按需复制桶和兼容展示分配；有大量玩家强制选择时再优先加入重复投影修复。前四项现已完成，对应实现、回归及分配量对照见第二轮报告。
