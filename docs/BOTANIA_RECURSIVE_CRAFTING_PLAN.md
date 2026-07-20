# Botania 递归合成集成计划

## 1. 范围与依据

```text
Minecraft: 1.20.1
Forge: 47.x
Mod: Botania 448 Forge
JAR: libs/[植物魔法] Botania-1.20.1-448-FORGE.jar
Mod ID: botania
```

目标 JAR 已通过项目 `libs/` 的 compile-only 依赖进入开发类路径，但当前没有 Botania 专用
module、recipe handler 或 batch delegate。Botania 的多数机器通过世界 ItemEntity、魔力网络、
多方块状态或方块转换执行，不能直接交给 `GenericBatchDelegate`。

## 2. 配方族可行性

| 配方族 | 第一版结论 | 核心原因 |
|---|---|---|
| Mana Infusion | Phase 1 | 确定输入/输出，但使用 Mana Pool 和世界物品实体 |
| Runic Altar | Phase 1 | 确定材料/产物/魔力，需显式完成动作和真实状态轮询 |
| Petal Apothecary | Phase 1 | 确定材料，需水、种子收尾及世界物品交互 |
| Botanical Brewery | Phase 2 | 需要容器/配方材料/魔力，输出与容器语义需单独建模 |
| Elven Trade | Phase 2 | 通过 Alfheim Portal 接收/生成世界物品，存在多输出 |
| Terrestrial Agglomeration | 暂缓 | 多方块结构、巨量魔力、世界实体和完成时序复杂 |
| Pure Daisy | 暂不支持 | 原地转换世界方块，不是可回滚的物品机器 |
| Orechid | 暂不支持 | 世界方块随机转换，输出及位置均非确定物品槽 |

## 3. 已确认的执行特征

- Mana Pool 和 Petal Apothecary 接收投入世界的 `ItemEntity`。
- Runic Altar 与 Botanical Brewery 具有内部材料集合，但正常交互依赖玩家/物品添加逻辑。
- Alfheim Portal 校验世界物品实体并在世界中生成交换结果。
- Terrestrial Agglomeration Plate 从世界收集材料并依赖结构与魔力。
- Pure Daisy 原地变换周围方块。
- Botania 魔力通过 `ManaReceiver`、Spark 或相邻网络提供，不是普通物品材料。

因此，所有第一阶段 delegate 都必须拥有 world-capture lease，并收集真实产物；禁止直接按 recipe
模板向 RS 补发结果。

## 4. 模块结构

建议新增：

```text
BotaniaRSModule
BotaniaReflection
BotaniaRecipeTypes
BotaniaWorldItemHelper
BotaniaManaProbe

ManaInfusionRecipeHandler
ManaPoolBatchDelegate

RunicAltarRecipeHandler
RunicAltarBatchDelegate

PetalApothecaryRecipeHandler
PetalApothecaryBatchDelegate
```

Phase 2 再新增：

```text
BotanicalBreweryRecipeHandler
BotanicalBreweryBatchDelegate
ElvenTradeRecipeHandler
ElvenTradeBatchDelegate
```

每个机器使用独立 `ModType` 和绑定类型，不能用一个 `botania` 类型让任意配方路由到错误机器。

## 5. 配方索引与结果提取

### 5.1 显式类型注册

module 注册时按 Botania recipe type 添加 handler。handler 必须：

- 使用目标 JAR 的公开 recipe 接口读取 ingredients、reagent、mana usage 和结果；
- 正确处理一个配方的多个输出；
- 排除世界方块转换、随机结果和无法枚举的结果；
- 为容器、种子、符文返还物等设置正确角色；
- 不通过反射猜测任意 `output/result` 字段。

### 5.2 索引规则

- 确定主输出进入普通候选索引。
- Elven Trade 多输出需要全部声明为 `OutputDeclaration`，不能把第一项之外的结果丢弃。
- Pure Daisy/Orechid 不进入物品递归索引。
- 相同 recipe ID 只产生一个 entry，主输出和次要输出反向查找后由 CandidateEngine 去重。
- reload 后清理 Botania recipe cache、mana probe cache 和 binding capability cache。

## 6. 需求角色与魔力

物品需求使用统一模型：

```text
CONSUMED            花瓣、符文材料、交易材料等
CATALYST            配方明确不消耗且批量期间可复用的物品
CONTAINER_RETURNING 容器参与后返还或替换
TRANSFORMED         输入容器/NBT 被 Botania 修改为结果
```

魔力、水和结构状态不 flatten 成普通材料：

- Mana cost 属于机器资源需求；
- Apothecary 的水属于机器状态；
- Runic Altar 的完成动作属于执行协议；
- Portal 是否开启属于结构和资源前置条件。

预览可显示这些警告，但最大制作次数只由会被永久消耗的物品决定。魔力不足应进入 WAITING，
而不是把一个“魔力物品”加入缺失材料。

## 7. Mana Pool 生命周期

1. 验证绑定方块是可接收魔力的 Mana Pool，且区块已加载。
2. 读取配方输入、输出、mana cost 和可能的 catalyst。
3. 获取机器独占 lease 和 world-capture lease。
4. 预扣真实输入，但尚不 settle。
5. 在受控捕获区域生成或交给 Botania 输入实体。
6. 观察 pool 接受输入、魔力下降及结果实体生成。
7. 捕获并核对真实结果，之后 settle 输入。
8. 捕获失败或结果被外部提取时进入 draining，不得模板补发。

外部磁铁、漏斗和玩家拾取属于竞争消费者。捕获区必须与项目现有
`CraftOutputInterceptor`/operation token 集成，并在成功、失败后及时关闭。

## 8. Runic Altar 生命周期

1. 验证 altar 空闲、魔力接收可用且没有他人材料。
2. 预扣每个配方材料，保留真实 NBT。
3. 通过 Botania 自己的 add-item 路径逐项放入，验证每项被接受。
4. 等待 altar 达到可完成状态。
5. 按 Botania 协议提供完成动作所需物品/交互，不直接调用 `assemble()` 跳过状态机。
6. 捕获真实符文产物和返还物。
7. 对照 `ExpectedProduction` 后 settle，清理 altar 和 capture lease。

批量执行默认串行。同一 altar 同时一个 operation；除非能证明材料集合和完成动作完全隔离，
否则不能跨节点并发复用。

## 9. Petal Apothecary 生命周期

1. 验证 Apothecary 空闲且装有配方所需液体。
2. 水状态只检查一次启动条件，不作为免费可伪造的物品产出。
3. 获取独占 lease，预扣花瓣和最终触发物。
4. 依次通过 Botania 接口投入材料，每次验证内部集合。
5. 最后提供触发物，捕获真实输出实体。
6. 成功后 settle；拒绝、中止时恢复仍可回收的投入物。

若液体会在合成后消耗，delegate 必须观察真实液体状态。没有可靠补水和回滚协议前，缺水只返回
可本地化错误，不自动消耗水桶伪造状态。

## 10. Phase 2 约束

### Botanical Brewery

- 容器必须作为 `TRANSFORMED` 或 `CONTAINER_RETURNING` 建模。
- 输出必须来自真实 brewery 状态。
- potion/brew NBT 必须使用精确 `MaterialKey`，不可只比较 Item。
- 批量时容器数量按每次消耗或转换放大，不得错误视作永久催化剂。

### Elven Trade

- 必须验证 Portal 结构、开启状态和持续魔力。
- 支持多输入、多输出和输出倍数。
- 所有结果实体必须由同一 capture token 归属和收集。
- Portal 关闭、区块卸载或捕获不完整时进入 draining。

## 11. 暂不支持的原因

### Pure Daisy

输入和输出是世界方块状态。自动化需要选址、保护校验、方块恢复、区块票和长时间 tick 所有权，
无法使用现有物品 ledger 给出可靠原子性。

### Orechid

存在随机世界方块转换和环境依赖，不能在计划阶段声明确定产物，也不能对失败做物品级退款。

### Terrestrial Agglomeration

虽然最终产物可枚举，但涉及多方块结构、世界实体聚合、长时间巨量魔力供给和外部拾取竞争。
第一版接入会显著扩大物品守恒风险，应在 Phase 1 的 world-capture 协议经实机验证后单独研究。

## 12. 并发、失败和防刷屏

- 每个物理机器默认并发度 1。
- 共享 Portal、Spark 网络或捕获区域默认互斥。
- WAITING 状态只更新进度快照；玩家消息使用现有去重/限频设施。
- 所有失败路径必须区分：未接受输入、已接受未产出、已产出未捕获、结果已被外部提取。
- 只有“未接受输入”允许完整退款；已产出路径必须 settle 已消费材料并保存捕获到的残余结果。
- 区块卸载、玩家下线和服务器停止都要关闭 capture lease，并保留可恢复状态。

## 13. 测试矩阵

单元测试：

- 每个 handler 的输入、输出、mana cost、角色提取；
- 多输出端口数量和批次放大；
- catalyst 最低数量不随 executions 翻倍；
- 精确 NBT brew 输出不会与普通物品合并；
- 不支持配方不会进入 RecipeIndex；
- settle/refund 和 capture token 恰好一次。

游戏内测试：

- Mana Pool 单次、批量、递归中间产物；
- Runic Altar 魔力不足等待、补足后继续；
- Apothecary 缺水、材料拒绝、成功收集；
- 玩家拾取、磁铁、漏斗竞争时不复制结果；
- 合成中途破坏机器、卸载区块、退出服务器后物品守恒；
- 配方树、总需求条、实际预扣数量一致；
- 等待状态不向聊天栏刷屏。

## 14. 验收顺序

1. 完成目标 JAR recipe/type API 的稳定探针测试。
2. 实现 Mana Infusion handler + delegate，并验证 world capture。
3. 实现 Runic Altar。
4. 实现 Petal Apothecary。
5. 完整测试与 `verifyReleaseJar` 通过后发布 Phase 1。
6. 单独评审 Brewery 与 Elven Trade，再决定 Phase 2。

实现中不得使用 recipe ID 特判，也不得用模板产物掩盖真实机器没有完成的问题。
