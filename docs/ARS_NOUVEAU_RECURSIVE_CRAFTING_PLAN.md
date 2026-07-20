# Ars Nouveau 递归合成集成计划

## 1. 范围与依据

目标版本：

```text
Minecraft: 1.20.1
Forge: 47.x
Mod: Ars Nouveau 4.12.6
JAR: libs/[新生魔艺] ars_nouveau-1.20.1-4.12.6-all.jar
Mod ID: ars_nouveau
```

本文基于目标 JAR 的类结构和现有 RS Integration 执行框架制定。当前已完成
Enchanting Apparatus 与 Imbuement Chamber 的核心生命周期调查；Scribes Table、
Potion Melder、Crystallizer、Wixie Cauldron 等机器尚未完成同等深度的反编译验证，
不得仅凭 JEI 展示就加入第一阶段。

## 2. 已确认语义

### 2.1 Enchanting Apparatus

- 中央 Enchanting Apparatus 保存核心输入或结果。
- 周围 Arcane Pedestal 提供配方材料。
- 合成消耗 Source，并通过 `attemptCraft` 显式触发。
- 完成后结果回到中央内部槽，而不是直接生成一个虚拟模板结果。
- `ArcanePedestalTile` 继承单槽容器能力，可通过 item handler 自动放置和取回物品。
- 机器和基座属于一个空间结构，绑定时不能只保存中央方块而忽略基座集合。

### 2.2 Imbuement Chamber

- `ImbuementTile` 的槽 0 同时承担输入槽和输出槽。
- 机器暴露 item handler capability，可自动插入和提取。
- 放入有效输入后自动运行，不需要额外点击触发。
- 机器从附近 Source 容器获取能量，并在内部保存 Source。
- 完成时产物替换槽 0 的输入。
- 仅观察“槽非空”无法判断完成，必须比较输入、预期输出和机器运行状态。

### 2.3 Source 不是物品材料

Source 是机器资源约束，不应转换成普通 `IngredientSpec`，也不能进入材料 DNF。
它应在预览中作为机器警告或资源需求展示，在执行阶段由 delegate 检查和等待。
Source 不足时不得提前扣除配方物品。

## 3. 支持阶段

### Phase 1A：Imbuement Chamber

优先实现。它是单方块、单槽、自动运行机器，生命周期最接近现有物理 delegate。

需要新增：

```text
ArsNouveauRSModule
ArsNouveauReflection
ArsImbuementRecipeHandler
ArsImbuementBatchDelegate
```

### Phase 1B：Enchanting Apparatus

在 Imbuement 稳定后实现。它需要空间基座分配、Source 检查和显式启动。

需要新增：

```text
ArsApparatusRecipeHandler
ArsApparatusBatchDelegate
ArsPedestalLayout
ArsSourceProbe
```

### Phase 2：其余工作站

只有在逐一确认以下信息后才能接入：

1. 权威配方注册源及结果提取方式；
2. 输入是否由物品槽、世界实体或玩家交互提供；
3. 能量、法术、药水或实体上下文是否影响结果；
4. 是否存在随机输出或世界副作用；
5. 是否能在中止时无损恢复输入。

未完成调查的机器默认不进入自动合成候选索引。

## 4. 配方索引设计

### 4.1 显式 handler

不得只依赖通用 `getResultItem(RegistryAccess)`。每种 Ars 配方使用显式 handler：

- 验证具体 recipe class 或 recipe type；
- 从权威输出字段或公开接口读取产物；
- 返回准确的 `IngredientSpec`；
- 标记容器返还物、可复用物和普通消耗物；
- 对空产物、随机产物或实体产物关闭自动化。

输出解析不得回调 `RecipeIndex.tryGetResultItem`，以免形成 handler 递归。

### 4.2 候选索引

- 只索引能得到确定物品输出的配方。
- 同一 recipe ID 只生成一个 `RecipeIndex.Entry`。
- 次要输出只有在真实机器能稳定收集时才声明。
- 配方 reload 后清理结果缓存、反射缓存和机器可用性缓存。

## 5. 统一需求模型

Ars 配方必须保留输入角色：

```text
CONSUMED            每次合成都消耗
CATALYST            必须存在，批量期间只保留最低数量
CONTAINER_RETURNING 每次参与并产生剩余物
TRANSFORMED         输入 NBT/耐久影响返回物或结果
```

Source 不属于上述物品角色。批量规划示例：

```text
材料 A x2 + 可复用核心 B x1 + 500 Source -> 产物 C
制作 10 次：A x20、B x1；执行资源需求为 5000 Source
```

Source 只限制能否启动或继续，不应让 `available / requiredPerCraft` 参与物品批次数计算。

## 6. 机器绑定

新增两个独立 `ModType`，避免把不同机器混在一个绑定类型中：

```text
ars_nouveau_imbuement
ars_nouveau_apparatus
```

绑定验证必须检查：

- 方块实体类型与维度；
- 区块已加载；
- 玩家保护权限；
- 机器当前空闲；
- Apparatus 周围有足够可用基座；
- Source 能力可读，且不会把另一个玩家正在进行的操作据为己有。

一个物理机器同一时刻只允许一个 RSI operation token。Apparatus 的中央块和全部被占用
基座共享同一个独占域。

## 7. Imbuement 执行生命周期

1. `validateAndInit` 读取配方、输入、输出、Source cost 和槽状态。
2. 预扣物品，但在机器接收前不结算 ledger。
3. 再次验证槽 0 为空并插入真实 NBT 输入。
4. 证明机器接受输入后提交 operation。
5. 轮询机器配方状态、进度和槽 0。
6. 仅当槽 0 与确定输出匹配时声明 DONE。
7. 提取真实槽中结果并交给 `NodeOutputAccumulator`。
8. 清理 operation lease；不得按模板补发结果。

中止时：若机器尚未启动，取回输入并退款；若已启动但状态未知，进入 draining，直到能证明
结果或可恢复输入，不能同时退款并交付结果。

## 8. Apparatus 执行生命周期

1. 捕获并排序周围可用基座，生成稳定的 pedestal layout。
2. 按 recipe ingredient 选择真实物品，并保留 NBT。
3. 将核心输入放入中央槽，周边材料放入各基座。
4. 最后一次检查 Source、结构、槽占用和 operation lease。
5. 调用 Ars 自身 `attemptCraft`，不模拟 `assemble()`。
6. 观察中央机器的真实运行状态。
7. 完成后从中央槽提取真实结果，并回收所有剩余物/催化剂。
8. 中止或启动拒绝时按槽逐一回收，之后才允许 ledger 退款。

放置顺序必须保证失败可逆：先占 lease，再放周边材料，最后放中央输入并触发。

## 9. Source 与并发

- Source 读数只能作为启动前快照，不能保证后续一定充足。
- Source 不足时返回 WAITING，使用限频状态提示，不能每 tick 向玩家发消息。
- 默认不允许同一 Source 网络上的多个 Apparatus 并行，除非能证明 Source 预留原子性。
- Imbuement 可按物理机器并行；每台机器同时一个 operation。
- 所有等待都有超时和可本地化原因，但超时不得自动复制或删除物品。

## 10. 测试与验收

单元测试：

- handler 能从目标版本 recipe class 提取准确输入、输出和 Source cost；
- 催化剂批量数量恒定，消耗材料随 executions 放大；
- 空输出、实体输出和未知配方不进入索引；
- Apparatus 基座布局稳定且不会重复占用一个基座；
- operation settle/refund 恰好一次。

游戏内验收：

- Imbuement 单次、批量、递归中间件都返回真实结果；
- 输入带 NBT 时结果保留 Ars 自身生成的 NBT；
- Apparatus Source 不足时等待且不刷屏、不扣料；
- 合成中断、区块卸载、玩家下线后材料守恒；
- 磁铁或漏斗不能让 RSI 同时退款并交付已被外部取走的结果；
- 配方树数量、总需求条和实际预扣一致。

## 11. 完成定义

只有满足以下条件才能把对应机器标记为 supported：

- 显式 recipe handler 与 delegate 均存在；
- 真实机器生命周期测试通过；
- Source 不被错误物品化；
- 催化剂和剩余物在成功、失败、中止路径都守恒；
- 完整测试与 `verifyReleaseJar` 通过；
- 不存在 recipe-ID 特判。
