# Botania 448 递归合成集成方案

## 1. 调研结论

目标环境：Minecraft 1.20.1、Forge 47.x、Botania `1.20.1-448-FORGE`，目标 JAR 为 `libs/[植物魔法] Botania-1.20.1-448-FORGE.jar`。

本方案已直接检查 JAR 中公开 API、配方实现和方块实体字节码。结论为：Mana Pool、Alchemy/Conjuration Catalyst、Petal Apothecary、Runic Altar、Botanical Brewery、Elven Trade 可以纳入一个完整交付；Terrestrial Agglomeration Plate 也作为正式支持项；Pure Daisy 作为专用世界方块转换正式支持；Mana Enchanter 本次不实现；Orechid 和其他随机魔力花不进入物品递归索引。

这不是分阶段方案。所有支持项一次性建立模块、索引、绑定、执行、恢复和测试，高风险项用配置开关控制。

## 2. JAR 已确认的 API

| 系统 | 配方接口/实现 | 关键公开信息 |
|---|---|---|
| 魔力池 | `ManaInfusionRecipe` | `matches(ItemStack)`、`getRecipeOutput(RegistryAccess, ItemStack)`、`getRecipeCatalyst()`、`getManaToConsume()` |
| 花药台 | `PetalApothecaryRecipe` / `RecipeWithReagent` | `getIngredients()`、`getReagent()`、`assemble()`；流体状态为 `EMPTY/WATER/LAVA` |
| 符文祭坛 | `RunicAltarRecipe` / `RecipeWithReagent` | `getIngredients()`、默认 reagent、`getManaUsage()`、`assemble()` |
| 植物酿造台 | `BotanicalBreweryRecipe` | `getBrew()`、`getManaUsage()`、`getOutput(containerStack)` |
| 精灵门 | `ElvenTradeRecipe` | `match(List<ItemStack>)`、`getIngredients()`、`getOutputs()`、动态 `getOutputs(inputs)`、`isReturnRecipe()` |
| 泰拉凝聚板 | `TerrestrialAgglomerationRecipe` | `getIngredients()`、`getMana()`、`assemble()` |
| 白雏菊 | `PureDaisyRecipe` | `getInput()`、`getOutputState()`、`getTime()`、`matches(...)`、`set(...)` |

`BotaniaRecipeTypes` 暴露 `MANA_INFUSION_TYPE`、`PETAL_TYPE`、`RUNE_TYPE`、`BREW_TYPE`、`ELVEN_TRADE_TYPE`、`TERRA_PLATE_TYPE`，可以通过 RecipeManager 稳定枚举，不需要猜测私有 output 字段。

## 3. 支持矩阵

| 机器 | 结论 | 真实执行与边界 |
|---|---|---|
| Mana Pool | 支持 | `collideEntityItem` 检查实体、配方和 mana，消耗一个输入并生成带 `manaInfusionSpawned` 标记的新实体。必须捕获真实实体。 |
| Alchemy Catalyst | 支持，魔力池子类型 | 下方 `BlockState` 匹配 `StateIngredient`。同一输入存在催化配方时，催化配方优先于普通配方。 |
| Conjuration Catalyst | 支持，魔力池子类型 | 同上；输出数量可大于输入数量，必须使用 `getRecipeOutput` 的实际 stack count。 |
| Petal Apothecary | 支持 | 先保证 `WATER`，无序投料，最后投入 `getReagent()`（通常为种子），由 `collideEntityItem` 完成并生成掉落物。 |
| Runic Altar | 支持 | 通过 `addItem` 无序投料，等待 `mana >= manaToGet`，投入活石，再调用 `onUsedByWand` 的完成入口。自动化无需真实法杖物品，但不能跳过活石或祭坛完成逻辑。 |
| Botanical Brewery | 支持 | 0 号槽必须先放实现 `BrewContainer` 的容器且尚无有效 Brew，之后加入材料；达到 `getManaCost()` 后由真实 tick 调用 `getOutput(container)`。 |
| Elven Trade | 保守支持 | Portal 保存 `stacksIn`，匹配后生成一个或多个实体。必须使用动态 `getOutputs(inputs)`，处理 return recipe，并持续校验结构、pylon 和 mana。 |
| Terra Plate | 支持 | 方块实体每 tick 从世界实体重建 inventory，结构有效才工作；材料被吸走会使 recipe 消失/中断。需要长时 capture lease。 |
| Pure Daisy | 支持，专用方块执行器 | 独立处理周围 8 格；放置输入方块，等待原生 `tickFlower` 调用 recipe `set`，再受控采集输出方块。仅索引可安全物品化的确定转换。 |
| Orechid/Marimorphosis | 禁用 | 随机、权重和环境相关的世界方块转换。 |
| 魔力花 | 不属于配方 | 不进入递归索引。 |

## 4. 模块与路由

新增：

```text
mods/botania/
  BotaniaRSModule.java
  BotaniaRecipeHandler.java
  BotaniaRecipeTypes.java
  BotaniaMachineProbe.java
  BotaniaWorldCapture.java
  BotaniaContainerResolver.java
  ManaPoolBatchDelegate.java
  PetalApothecaryBatchDelegate.java
  RunicAltarBatchDelegate.java
  BotanicalBreweryBatchDelegate.java
  ElvenTradeBatchDelegate.java
  TerraPlateBatchDelegate.java
  PureDaisyBlockConversionDelegate.java
```

注册独立 `ModType`：`botania_mana_pool`、`botania_alchemy_catalyst`、`botania_conjuration_catalyst`、`botania_apothecary`、`botania_runic_altar`、`botania_brewery`、`botania_elven_trade`、`botania_terra_plate`、`botania_pure_daisy`。所有类型（包括 `botania_terra_plate`）进入正式注册。不能使用单一 `botania` 类型，否则同一物品可能被路由到错误的魔力池变种。

`BotaniaRecipeHandler` 直接按上述 RecipeType 分派。Mana Infusion 的索引 key 必须包含 catalyst `StateIngredient`；无 catalyst 为普通池，有 catalyst 的条目按可显示 BlockState 映射到具体绑定类型。无法唯一映射的第三方 catalyst 条目不进入自动执行索引，并记录诊断日志。

## 5. 容器、NBT 与材料角色

输入角色：

- `CONSUMED`：花瓣、符文材料、精灵交易材料、活石、种子等 reagent。
- `CATALYST`：只用于配方匹配且不被机器消耗的世界催化方块。
- `CONTAINER_RETURNING`：水桶等补水容器，消耗内容后返回空容器。
- `TRANSFORMED`：Brewery 容器；输入 stack 被转换为带 NBT 的输出。

Botanical Brewery 不能预先固定一个输出物品。handler 必须针对每个可接受的 `BrewContainer` 调用 `getOutput(containerStack)` 生成精确输出声明。若存在多种容器，则每种容器产生独立候选，使用 Item+NBT 的 `MaterialKey` 去重。未知第三方容器只有在实现 `BrewContainer` 且 `getOutput` 返回稳定非空结果时才可索引。

## 6. 花药台自动补水

`PetalApothecary` API 只暴露 `setFluid(State)`/`getFluid()`，状态包括 WATER 和 LAVA。自动执行前：

1. 若状态为 WATER，直接继续。
2. 若为 EMPTY，按 `virtualWater` 配置决定补水策略：
   - `virtualWater=false`（默认）：从 RS 网络预扣一个已配置的有效水容器，优先通过 `PetalApothecaryBlock` 的真实 use 路径交互，并结算空桶/容器返回。
   - `virtualWater=true`：允许 delegate 调用 `setFluid(WATER, false)` 直接补水，不生成或消耗水桶；该操作记录为机器配置提供的虚拟资源，不伪造物品产出。
3. 两种策略都必须触发方块实体同步并确认 `getFluid()==WATER` 后才投料。虚拟水只改变水源前置条件，不改变配方材料、产物和 mana。
4. 若状态为 LAVA，不覆盖，返回机器占用错误。
5. 真实补水没有有效水容器时进入 WAITING；虚拟补水则直接继续。

重复上次配方的 `trySetLastRecipe` 是玩家便利功能，不作为自动化主路径。delegate 已持有确定 recipe，应逐项投料并验证容器 inventory，避免依赖方块实体短时保存的 `lastRecipe`。

## 7. 符文祭坛完成协议

JAR 的 `onUsedByWand` 实际完成条件为：存在当前 recipe、`manaToGet > 0`、mana 已满、祭坛周围存在活石实体。随后它扣 mana、调用 recipe `assemble`、生成带 `runicAltarSpawned` 标记的产物并消耗材料/活石。

因此“法杖可以跳过”的正确实现是：不从网络申请或消耗森林法杖，delegate 在充能完成并投放活石后直接调用祭坛的完成入口。不得在 delegate 中自行调用 `assemble()` 和生成结果，否则会绕过材料清理、返回物、mana 扣除、事件与实体标记。空手右键重复配方同样不作为主路径。

## 8. 通用执行与守恒

每次 operation 保存 recipe id、类型、维度/位置、下方 BlockState、结构快照、预扣输入、预期输出、容器 key、operation token、capture lease 和当前状态。

执行顺序：

1. 验证绑定、区块、机器空闲、结构和 recipe variant。
2. 先满足非物品状态；花药台先自动补水，Portal/泰拉板先验证多方块。
3. 创建独占 token 和捕获区域，预扣材料但不 settle。
4. 通过机器自己的 `collideEntityItem`、`addItem` 或完成入口投料。
5. 观察 mana/水/内部 inventory/实体，捕获带 Botania 标记或 operation 所有权的真实输出。
6. 与动态 `ExpectedProduction`（数量和 NBT）完全匹配后 settle。
7. 失败区分未接受、已接受未产出、已产出未捕获、外部吸走；只有未接受允许完整退款。

每台物理机器并发度为 1。共享 Portal、Spark 网络或捕获 AABB 时互斥。WAITING 只更新状态，不刷聊天消息。

## 9. 泰拉凝聚板执行协议

`TerrestrialAgglomerationPlateBlockEntity` 每 tick 获取平台上方的 `ItemEntity`，把 stack 展平为临时 inventory，再通过 `TERRA_PLATE_TYPE` 匹配 recipe；只有 `hasValidPlatform()` 成立时才积累 mana。配方提供确定的 `getIngredients()`、`getMana()` 和 `assemble()`，因此能够进入普通递归图。

执行时先验证多方块和唯一绑定，随后取得覆盖平台材料区域的独占 world-capture lease，再一次性投放本次 recipe 的精确材料实体。delegate 保存所有投入实体 UUID、原始 stack、recipe id、目标 mana 和 `getCompletion()`；充能期间禁止漏斗、磁铁、玩家和其他 operation 提取或追加实体。每 tick 重新确认 recipe 仍匹配、结构仍有效且实体集合未变化。

完成必须由 Botania 自己的 `serverTick` 消耗材料并生成真实产物，delegate 只捕获和结算。结构破坏、实体缺失或 recipe 改变时立即停止投入并进入 draining：仍存在且归属于本 operation 的材料可回收；已经被外部提取的材料不得补发；已生成的产物只收集一次。区块卸载和服务端停止时持久化 UUID/stack/mana 快照，恢复后以世界实体和方块实体实际状态为准，快照只用于核对，不能据此生成物品。

批量执行严格串行，每次只投一份配方材料，上一份产物捕获和 settle 完成后才能开始下一份。巨额 mana 仅造成 WAITING/长时间运行，不影响递归可行性。

## 10. 暂不支持的系统

Mana Enchanter（魔力附魔台）本次不实现、不注册 `ModType`、不建立候选，也不进入递归图。Orechid/Marimorphosis 等随机世界转换同样不支持。

## 11. 白雏菊方块转换协议

`PureDaisyBlockEntity` 固定扫描同一高度周围 8 个位置，每个位置有独立 `ticksRemaining`。发现 `PURE_DAISY_TYPE` 匹配后使用 recipe `getTime()` 计时；输入状态持续匹配到倒计时结束时，由 Botania 调用 recipe `set(level, pos, flower)` 完成真实方块转换。`StateCopyingPureDaisyRecipe` 还可能复制输入方块状态，因此不能假定输出永远是默认 BlockState。

为了接入物品递归，使用专用 `PureDaisyBlockConversionDelegate`，不复用 ItemEntity delegate：

1. 绑定白雏菊并取得其周围 8 格的独占 block lease；只使用空气且允许放置/破坏的位置。
2. 从 `getInput().getDisplayedStacks()` 建立可放置输入候选，但执行前必须用实际放置后的 BlockState 再调用 recipe `matches(...)` 验证。
3. 从 RS 预扣方块物品，通过受控 fake player 的正常放置路径放入空位；禁止直接 `setBlock` 伪造输入或绕过保护事件。
4. 等待白雏菊自己的 tick 完成，逐格核对实际 BlockState。玩家破坏、活塞移动、其他方块覆盖或区块卸载时停止该格操作。
5. 转换完成后通过受控 fake player 和配置工具正常采集，使用实际 loot/drop 进入 capture token；活石/活木等掉落自身的方块可直接支持。
6. 只有实际掉落与索引声明一致才 settle。输出没有对应物品、需要特定工具/附魔、掉落随机、带 BlockEntity 数据或执行 success function 会产生额外不可枚举副作用的配方，保留世界转换但不进入物品递归索引。

8 格可并行承载同一 operation 的独立执行，但为简化守恒，默认一次填满不超过计划数量，逐格拥有独立 token/状态；批量剩余数量在空位释放后继续。恢复时以实际方块状态为准：仍是输入则继续等待，已是输出则进入采集，其他状态视为外部干预，绝不按快照补发。

原版原石到活石、原木到活木属于确定输出且正常掉落自身，可正式进入递归图。这里的“魔力花转化活石”应在 UI/文档中显示为“白雏菊方块转换”，避免与产 mana 的产能花混淆。

## 12. 索引、恢复与测试

- Elven Trade 声明全部输出，使用动态输出处理保留/返回输入的配方。
- 魔力池输出使用输入 stack 调用 `getRecipeOutput`，保留 NBT 与倍率。
- reload 清除 Botania recipe、catalyst 映射、容器、mana probe 和 binding cache。
- operation 持久化后恢复前重新验证实体 UUID、机器状态和结构；不能仅凭旧快照补发结果。
- Terra Plate 正式进入索引；只有结构无效或无法取得独占捕获 lease 时才标记当前机器不可执行。
- Pure Daisy 的确定且可安全放置/采集配方进入专用方块转换索引；Orechid 永不进入普通索引。

单元测试覆盖 RecipeType 路由、催化器优先级、动态输出、容器枚举、NBT、reagent、mana、批量倍率、自动补水及空桶返回、祭坛无实体法杖完成、多输出和 exactly-once settle。游戏内测试覆盖外部漏斗/磁铁/玩家拾取、魔力不足、LAVA 花药台、结构破坏、区块卸载、重启恢复、第三方 BrewContainer，以及白雏菊 8 格并行/外部改块/重启恢复。

验收要求：`test`、`build`、`verifyReleaseJar` 通过；所有开启机器从真实 Botania 状态产出；任何失败路径不复制、不吞物；禁用系统不会被 Generic handler 误索引。

## 13. 最终可行性

魔力池含两种催化器、花药台、符文祭坛、酿造台、精灵门的可行性高。泰拉凝聚板同样可正式支持，但必须独占平台上方实体区域直到完成，并把长时间充能状态持久化。白雏菊的原石到活石、原木到活木也可正式支持，但必须占用 8 格区域并通过真实放置、原生转换和真实采集完成物品守恒。魔力附魔台留待单独设计，本次不实现。自动补水和跳过实体法杖均可实现。花药台支持默认真实水容器模式，也支持显式 `virtualWater` 虚空补水模式；后者只提供水状态，不产生物品。法杖跳过仍必须调用祭坛原生完成入口而不是模板补发。
## 新增反编译验证：凝矿兰与火凝矿兰

从 Botania 1.20.1-448 JAR 逐项读取了 `orechid` 与 `orechid_ignem` 配方。它们不是普通的确定性机器配方：同一输入方块对应多个输出，每次由权重随机选择；输入还区分石头、深板岩和下界岩。例如：

- 凝矿兰：石头 -> 煤矿 67415（57.478%）、铁矿 29371（25.042%）、红石矿 7654（6.526%）、铜矿 7000（5.968%）、金矿 2647（2.257%）、绿宝石矿 1239（1.056%）、青金石矿 1079（0.920%）、钻石矿 883（0.753%）。
- 凝矿兰：深板岩 -> 深层铁矿 250（25%）、深层青金石矿 175（17.5%）、深层红石矿 150（15%）、深层金矿 125（12.5%）、深层钻石矿 100（10%）等。
- 火凝矿兰：下界岩 -> 下界石英矿 19600（83.822%）、下界金矿 3635（15.545%）、远古残骸 148（0.633%）。

因此凝矿兰/火凝矿兰不能安全转换成单一递归输出：如果把“石头 -> 钻石矿”登记为确定性配方，会产生错误产物率；如果把所有可能输出都登记，又会让递归规划器把随机结果当成必得资源。当前实现明确排除 `orechid` 和 `orechid_ignem`，只保留确定性 Mana Pool、花药台、符文祭坛、植物酿造台、精灵门、泰拉凝聚板、白雏菊等机器。

另外验证了精灵贸易的 `diamond_return` 是钻石输入、钻石输出的原样返回配方，不能作为资源生产配方；魔力池 `coal_dupe` 则是催化器下煤炭 1 -> 2 的确定性复制配方，会由魔力池催化器支持。