# RS Integration 性能与历史债务审查

> 审查日期：2026-07-27  
> 审查范围：`src/main/java`、网络协议、递归规划、异步执行、共振盘与长期服务器状态  
> 本文只记录研究结论，不包含代码修改。

## 1. 执行摘要

项目目前没有明显的“所有玩家持续掉 TPS”式单点问题，但存在三类需要优先处理的风险：

1. **大型计划的协议上限与配置上限不一致**，可能导致计划能生成却无法发送进度；
2. **计划构建和完整进度快照都在服务端主线程执行**，节点规模增大后会同时放大 CPU、分配和网络开销；
3. **共振盘被动效果每 tick 复制整个磁盘库存**，这是最明确、最容易安全优化的持续热路径。

此外，执行系统长期保留“平铺步骤 + DAG 图”两套表达，核心类已经过大。它暂时不是直接性能故障，但显著提高了每次修复产生回归的概率。

建议按以下顺序处理：

1. 统一计划节点上限与进度协议上限；
2. 优化共振盘扫描和无变化同步包；
3. 将运行进度改为增量同步；
4. 补齐规划阶段的性能测量，再决定是否异步化；
5. 修复有界缓存和玩家生命周期清理；
6. 最后分阶段拆分大型核心类。

## 2. 方法与限制

本次审查采用静态代码分析：

- 搜索所有 Forge tick 监听器；
- 检查网络包编码与集合上限；
- 检查静态 `Map` / `Set` 的容量和清理生命周期；
- 检查递归规划运行线程；
- 检查每 tick 的集合复制、注册表查询和反射；
- 统计核心类规模和兼容路径。

没有运行真实服务器采样，因此本文将问题分为：

- **已确认**：由代码路径可以直接证明；
- **高概率风险**：复杂度明确，但影响大小依赖整合包和玩家行为；
- **历史债务**：主要影响维护和回归风险，而非当前 TPS。

## 3. 已确认问题

### P0：计划上限与进度包上限冲突

#### 证据

- `RSIntegrationConfig` 允许 `craftingMaxSteps` 配置到 `16384`；
- `CraftProgressPacket.MAX_NODES` 固定为 `4096`；
- 编码时节点超过 4096 会抛出 `IllegalArgumentException`；
- 运行链每秒构建并发送一次完整节点列表。

相关位置：

- `src/main/java/com/huanghuang/rsintegration/config/RSIntegrationConfig.java:562`
- `src/main/java/com/huanghuang/rsintegration/crafting/batch/CraftProgressPacket.java:18`
- `src/main/java/com/huanghuang/rsintegration/crafting/batch/CraftProgressPacket.java:43`
- `src/main/java/com/huanghuang/rsintegration/crafting/AsyncCraftChain.java:3856`

#### 影响

节点数位于 4097 到 16384 之间的计划可能成功解析并开始执行，却在状态同步阶段失败。这不仅是性能问题，也是协议正确性问题。

#### 建议

短期必须选择一个一致契约：

- 将服务端计划节点上限限制为 4096；或
- 提升协议上限，并同时加入分片/增量同步，避免单包过大。

不建议只把 `MAX_NODES` 改为 16384。那会绕过异常，但可能制造数 MB 级状态包。

### P1：共振盘每 tick 复制全部物品

#### 证据

`TickSimulator.simulate` 每个服务端玩家每 tick 调用：

1. `snapshotStacks` 复制磁盘全部 `ItemStack`；
2. 对每个 Stack 查询注册表 ID；
3. 线性扫描白名单；
4. 匹配项目再执行一次或多次复制。

相关位置：

- `src/main/java/com/huanghuang/rsintegration/resonance/passive/PassiveEffectEngine.java:60`
- `src/main/java/com/huanghuang/rsintegration/resonance/passive/TickSimulator.java:26`
- `src/main/java/com/huanghuang/rsintegration/resonance/passive/TickSimulator.java:57`
- `src/main/java/com/huanghuang/rsintegration/resonance/passive/TickSimulator.java:93`

#### 复杂度

设磁盘中有 `S` 个 Stack、白名单有 `W` 项、在线玩家数为 `P`：

```text
每秒扫描量约为 P * 20 * S
匹配查找约为 P * 20 * S * W
ItemStack 复制至少为 P * 20 * S
```

磁盘越大、在线玩家越多，短命对象分配越明显。

#### 建议

- 将白名单预编译为 `Map<Item, WhitelistEntry>`；
- 先在原始 Stack 上判断是否命中，再复制命中项；
- 对不会每 tick 生效的兼容物品允许配置执行间隔；
- 保留可变物品的槽位事务语义，不要直接修改 RS 返回的共享 Stack。

这是收益明确、行为边界清晰、最适合首先实施的性能优化。

### P1：共振盘状态无变化仍每秒同步

#### 证据

`syncDiskState` 已经比较 `gemCount`、催化剂掩码和能力掩码，并维护 revision；但无论值是否变化，方法最后都会发送 `ResonanceSyncPacket`。

相关位置：

- `src/main/java/com/huanghuang/rsintegration/resonance/passive/PassiveEffectEngine.java:78`
- `src/main/java/com/huanghuang/rsintegration/resonance/passive/PassiveEffectEngine.java:98`

#### 影响

每个在线玩家每秒固定收到一个状态包。单包不大，但这是完全可以避免的稳定流量，且已有 revision 机制却没有利用。

#### 建议

- 首次发现磁盘时发送；
- 状态变化时发送；
- 每 30 到 60 秒发送一次校准包；
- 磁盘消失也必须发送一次清空状态。

### P1：进度同步为完整快照而非增量

#### 证据

`AsyncCraftChain` 每 20 tick 调用 `buildProgressSnapshot(false)`，遍历完整 DAG；`CraftProgressPacket` 随后编码每个节点的配方 ID、模组类型、输出 Stack、操作计数、机器位置、原因和技术详情。

相关位置：

- `src/main/java/com/huanghuang/rsintegration/crafting/AsyncCraftChain.java:1802`
- `src/main/java/com/huanghuang/rsintegration/crafting/AsyncCraftChain.java:1871`
- `src/main/java/com/huanghuang/rsintegration/crafting/AsyncCraftChain.java:3854`
- `src/main/java/com/huanghuang/rsintegration/crafting/batch/CraftProgressPacket.java:46`

#### 粗略估算

节点内容长度取决于字符串和 NBT。即使按每节点 80 到 150 字节估算：

| 节点数 | 单次快照估算 | 每秒/每任务 |
|---:|---:|---:|
| 256 | 20-38 KB | 20-38 KB/s |
| 1024 | 80-150 KB | 80-150 KB/s |
| 4096 | 320-600 KB | 320-600 KB/s |

这还不包含 Netty/Minecraft 包装开销，也不包含服务端构建对象和客户端解码成本。

#### 建议协议

```text
CraftStartedPacket       完整节点静态信息，仅一次
CraftProgressDeltaPacket 只包含状态变化的节点
CraftProgressPacket      低频完整校准、重连恢复、终止状态
CraftStatusRequestPacket 客户端检测序列缺口时主动请求
```

现有 `sequence` 和状态恢复请求已经提供了增量协议所需的大部分基础。

### P2：计划缓存没有真正的硬上限

#### 证据

`PLAN_CACHE` 注释称缓存过大时清理，但超过 64 条后只删除超过 500ms TTL 的条目。若短时间写入大量不同 key，所有条目都未过期，缓存仍可继续增长。

相关位置：

- `src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java:100`
- `src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java:2341`

#### 建议

先清理过期项，再按创建时间淘汰最旧条目，直到 `size <= 64`。同时把缓存 key 改成结构化 record，避免构造长字符串。

### P2：部分玩家限流缓存缺少退出清理

#### 证据

以下静态缓存只写入，没有玩家退出或服务器停止清理：

- `CraftCancelPacket.CANCEL_COOLDOWN`
- `CraftStatusRequestPacket.STATUS_COOLDOWN`

相关位置：

- `src/main/java/com/huanghuang/rsintegration/crafting/batch/CraftCancelPacket.java:35`
- `src/main/java/com/huanghuang/rsintegration/crafting/batch/CraftStatusRequestPacket.java:26`

#### 影响

每位曾使用功能的玩家永久保留一个 UUID 和时间戳。单项很小，不会造成短期开服故障，但属于明确的生命周期债务。

#### 建议

将所有玩家级限流器统一到一个有：

- `onPlayerLogout(UUID)`；
- `clearServerState()`；
- TTL 清理；
- 测试覆盖

的公共组件中。

## 4. 高概率性能风险

### P1：计划预览在服务端主线程完整计算

#### 证据

`GenericCraftPacket.handle` 使用网络上下文的 `enqueueWork`，随后直接调用 `tryBuildPlan`。该方法执行配方解析、库存统计、替代配方处理、材料合并和计划图构建。

相关位置：

- `src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java:392`
- `src/main/java/com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket.java:1213`

`craftingMaxSteps` 默认 4096，最大 16384，因此一次 JEI 操作可能在一个服务端 tick 内完成大量工作。

#### 为什么不能直接丢进线程池

规划过程读取：

- Minecraft `RecipeManager`；
- 玩家背包与菜单；
- RS 网络缓存；
- 机器绑定和世界状态；
- 第三方模组配方/反射对象。

这些对象并不都保证线程安全。简单异步化可能引入库存竞态、世界访问崩溃和配方重载竞态。

#### 推荐拆法

1. 主线程采集不可变快照：配方索引、库存计数、绑定摘要、配置版本；
2. 后台执行只依赖不可变数据的纯规划；
3. 回主线程重新验证库存版本和绑定版本；
4. 过期结果丢弃并提示重新预览。

在实施前必须先增加规划计时，因为当前 `PerformanceMonitor` 只覆盖 `AsyncCraftManager` 的执行 tick，不覆盖 `tryBuildPlan`。

### P2：计划响应同时发送平铺步骤和完整 DAG

#### 证据

`PlanResponsePacket` 先序列化 `plan.steps()`，随后又序列化 `plan.graph()`。大量配方、输入、输出和替代项会在两种投影中重复出现。

相关位置：

- `src/main/java/com/huanghuang/rsintegration/crafting/plan/PlanResponsePacket.java:83`
- `src/main/java/com/huanghuang/rsintegration/crafting/plan/PlanResponsePacket.java:300`

#### 建议

短期先记录编码后的字节数和节点数。长期应选择一个服务端权威模型：客户端从 DAG 派生列表视图；仅为旧协议保留平铺步骤，不在新协议重复发送。

### P2：邻近 RS 节点兜底扫描

#### 证据

网络解析失败时，会每位玩家每 10 秒扫描约 `17 * 9 * 17 = 2601` 个方块位置，检查区块和 BlockEntity。

相关位置：

- `src/main/java/com/huanghuang/rsintegration/network/RSIntegrationNetwork.java:318`

当前有冷却和过期清理，因此不是泄漏。但大量没有终端/绑定物品的玩家持续触发网络解析时，会形成周期性扫描尖峰。

#### 建议

- 只在明确需要网络的用户操作时扫描，不在普通每 tick 逻辑调用；
- 成功解析后缓存网络节点位置；
- 失败结果也使用短 TTL 缓存；
- 统计扫描次数和耗时，确认是否实际成为热点。

### P2：FTB Quests 离线条目可持续重排队

`ExternalItemProgressBridge` 找不到在线玩家或任务系统未就绪时，会把批次重新放回 pending map。服务器停止时会清理，但玩家长期离线时条目会一直存在。

相关位置：

- `src/main/java/com/huanghuang/rsintegration/compat/ftbquests/ExternalItemProgressBridge.java:22`
- `src/main/java/com/huanghuang/rsintegration/compat/ftbquests/ExternalItemProgressBridge.java:76`

建议为 pending 条目增加创建时间、最大保留时间和数量上限。

## 5. 已有的良好设计

以下部分已经处理得比较稳，不建议为了“优化”而重写：

- `MaterialSources` 使用每玩家、每 tick 缓存合并库存；
- `CraftPacketUtils` 的配方材料缓存会在配方重载时清理；
- 侧边栏存储监听器在玩家退出和服务器清理时注销；
- 机器状态只对 dirty 玩家按 40 tick 推送，并发送变化项；
- `AsyncCraftManager` 对 active chain 使用快照遍历，完成后移除；
- 多数反射探针已经缓存 Field/Method 或缺失标记；
- 网络解码普遍具有数量和字符串长度上限；
- 材料预留、提交、退款、产物捕获具备较完整的事务边界。

这些能力是当前稳定性的基础。性能优化不应绕过事务账本、机器租约或服务端最终验证。

## 6. 主要历史债务

### 6.1 核心类职责过多

| 文件 | 规模 | 当前职责 |
|---|---:|---|
| `AsyncCraftChain` | 3978 行 / 约 105 方法 | DAG/平铺执行、机器租约、退款、异步配方、进度、终止审计 |
| `GenericCraftPacket` | 2544 行 | 协议、入口验证、计划构建、缓存、执行路由、特殊模组分支 |
| `CraftingPlanScreen` | 2395 行 | 状态、布局、绘制、输入、网络请求、模组专用操作 |
| `CraftPacketUtils` | 1473 行 | 材料抽取、反射、配方兼容、库存工具、结果处理 |
| `ExtractionLedger` | 1362 行 | 预留、网络/背包扣除、提交、回滚、退款、缓存 |

代码体积本身不是 bug，问题是这些类同时跨越多个所有权边界。新兼容逻辑很容易误改通用事务路径。

### 6.2 平铺执行与 DAG 执行长期并存

`AsyncCraftChain` 构造 DAG 后仍生成 `compatibilitySteps`，并根据策略在图执行和平铺执行之间切换。计划响应也同时携带 steps 和 graph。

相关位置：

- `src/main/java/com/huanghuang/rsintegration/crafting/AsyncCraftChain.java:200`
- `src/main/java/com/huanghuang/rsintegration/crafting/AsyncCraftChain.java:334`
- `src/main/java/com/huanghuang/rsintegration/crafting/plan/PlanResponse.java:47`

这使以下逻辑需要维护两份：

- 节点进度；
- 中间材料可见性；
- 重复次数；
- 失败原因；
- 产物回收；
- 串行/并行判断。

Clibano 和 PGP 中间步骤问题都与两种执行语义的边界有关。

#### 收敛建议

不要直接删除平铺路径。先完成：

1. 为每种 ModType 标注只能使用平铺路径的原因；
2. 增加图执行与平铺执行结果等价测试；
3. 把共同事务操作提取为服务；
4. 逐个迁移兼容模组；
5. 最后把平铺路径降为少量 legacy adapter。

### 6.3 特殊模组逻辑进入通用链

`AsyncCraftChain` 内含 Enigmatic Legacy Earth Heart 等具体物品流程。`GenericCraftPacket` 同时处理 FA、WR、Embers 等专用数据。

这些逻辑应逐步移入：

- `RecipeExecutionContract`；
- 专用 delegate；
- 计划附加数据 codec；
- 通用的异步玩家转换接口。

目标不是减少文件行数，而是保证新增模组不会继续修改核心事务状态机。

### 6.4 性能监控覆盖不足

现有 `PerformanceMonitor` 只记录：

- 执行链每 tick 总耗时；
- resolve timeout 次数；
- active chain 数量。

它无法回答：

- 哪个配方规划最慢；
- 计划包含多少节点；
- 进度包和计划包多大；
- 共振盘每 tick 扫描多少 Stack；
- 哪种 delegate 占用时间最长；
- 主线程时间花在解析、库存快照还是机器轮询。

建议新增低开销直方图或累计计数，并只在调试命令中输出：

```text
plan.count / plan.avg_us / plan.max_us
plan.nodes.avg / plan.nodes.max
packet.plan.bytes / packet.progress.bytes
resonance.scanned_stacks / resonance.matched_stacks
delegate.tick_us by modType
nearby_network_scan.count / max_us
```

## 7. 分阶段优化计划

### 阶段 A：低风险、立即收益

1. 统一 4096/16384 节点上限；
2. TickSimulator 白名单改为 Item 索引，只复制命中 Stack；
3. 共振盘状态仅变化时发包；
4. PLAN_CACHE 增加硬容量；
5. 清理玩家限流缓存；
6. 增加关键性能计数。

### 阶段 B：网络与大计划

1. 首次发送完整进度，后续发送 delta；
2. 计划包记录实际编码大小；
3. DAG 成为新协议的唯一计划表达；
4. 超大计划采用分页或分片，而不是扩大单包上限。

### 阶段 C：规划线程模型

1. 定义不可变 `PlanningSnapshot`；
2. 把纯图计算从世界/网络读取中拆开；
3. 后台规划增加 request generation 和取消；
4. 回主线程验证库存和绑定版本；
5. 保留同步回退，防止第三方配方对象线程不安全。

### 阶段 D：历史债务收敛

1. 从 `AsyncCraftChain` 提取 ProgressPublisher；
2. 提取 TerminationService；
3. 提取 FlatStepAdapter；
4. 从 `GenericCraftPacket` 提取 PlanRequestService 和 PlanCache；
5. 将模组专用附加状态移出通用类。

每一步都应保持入口和协议兼容，禁止一次性重写执行链。

## 8. 验证方案

### 基准场景

至少准备以下测试：

| 场景 | 规模 |
|---|---|
| 普通工作台递归 | 20-100 节点 |
| 九重压缩类配方 | 500-4000 节点 |
| 多机器并发 DAG | 8-32 个同时运行节点 |
| 大型共振盘 | 1000、5000、10000 个 Stack |
| 多玩家共振盘 | 10、30、50 玩家 |
| 无网络玩家 | 持续打开普通容器并触发解析 |

### 验收指标

- 单次计划构建最大主线程耗时；
- 运行链 tick 的 p50/p95/p99；
- 每秒 S2C 字节数；
- 每 tick 分配量和 GC 次数；
- 共振盘扫描/命中 Stack 数；
- 退款、产物交付和取消后的账本审计结果；
- 平铺与 DAG 执行结果一致性。

### 正确性红线

任何性能优化都不能破坏：

- 材料只扣一次；
- 中间产物只发布一次；
- 失败后不同时退款输入并保留真实产物；
- 机器租约不会被两个任务同时持有；
- 玩家离线后仍能安全终止；
- 配方重载后不使用旧 Recipe 对象；
- 客户端不能决定服务端最终材料和机器选择。

## 9. 最终判断

当前最急的不是重写 `AsyncCraftChain`，而是先修复节点上限契约、共振盘热路径和完整进度包。这三项都有明确证据，且可以在不改变合成入口和事务模型的前提下完成。

主线程规划很可能是大型计划的主要卡顿来源，但在没有计时数据前不应盲目异步化。正确顺序是先补测量、建立不可变快照边界，再逐步迁移纯计算部分。

历史债务方面，最需要长期收敛的是“平铺兼容执行与 DAG 执行双轨制”。它已经多次成为中间步骤问题的根源，但迁移必须依靠等价测试逐步完成，不能一次性删除旧路径。
