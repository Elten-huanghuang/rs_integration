# Lychee Tweaker 兼容性评估

目标文件：`Lychee-1.20.1-forge-5.1.14.jar`
目标环境：Minecraft 1.20.1 Forge，当前 `rs-integration` 自动合成架构

## 结论摘要

**可以兼容，但不能把 Lychee 当成普通 `Recipe` 直接塞进现有 Generic Delegate。**

Lychee 的配方虽然实现了 Minecraft 的 `Recipe` 接口，也能被 `RecipeManager` 找到，但它的核心语义不是“给几个物品，调用 `assemble()` 得到一个固定结果”，而是：

```text
输入物品
  + 世界/方块/实体/天气/时间等上下文条件
  + 随机次数与概率
  + 一组 post actions
  -> 对世界、输入物品、实体或玩家执行动作
```

因此建议分三档处理：

| 类型 | 建议 | 原因 |
|---|---|---|
| 纯物品输入，确定性物品输出 | **可兼容** | 可以转成 RS 的逻辑合成步骤，并通过专用 delegate 执行或安全地产出 |
| 需要固定世界条件，但输出可枚举 | **有限兼容** | 需要把条件检查、机器/世界位置和动作执行纳入 delegate，不能只读 `getResultItem()` |
| 爆炸、闪电、实体交互、命令、延迟、随机动作等 | **默认不自动化** | 具有全局世界副作用、上下文依赖或不可安全回滚，自动化容易破坏物品守恒 |

**第一版推荐只支持“物品型、无世界副作用、确定性输出”的 Lychee 配方。** 其余配方可以继续在 JEI/Lychee 原界面展示，但不要出现在 RS 自动合成候选中。

## JAR 中发现的配方类别

Lychee 5.1.14 的核心配方类包括：

| Lychee 类别 | 典型语义 | RS 自动合成评价 |
|---|---|---|
| `snownee.lychee.core.recipe.ItemShapelessRecipe` | 多个物品输入的无序配方 | **最适合第一阶段** |
| `snownee.lychee.core.recipe.ItemAndBlockRecipe` | 物品输入 + 方块条件 | 需要世界位置，有限兼容 |
| `snownee.lychee.crafting.ShapedCraftingRecipe` | Lychee 扩展版有序工作台配方 | 可按工作台配方处理，但要保留 post actions |
| `snownee.lychee.anvil_crafting.AnvilCraftingRecipe` | 铁砧左/右输入、等级成本、耐久成本 | 可做专用铁砧路径，不能按普通物品配方执行 |
| `snownee.lychee.interaction.BlockInteractingRecipe` | 物品与方块交互 | 需要实际方块位置和交互事件 |
| `snownee.lychee.item_inside.ItemInsideRecipe` | 物品放入指定方块/容器中，可能等待一段时间 | 默认需要实体/方块状态和 tick；通过显式催化剂映射的确定性子集可走磁盘虚拟路径 |
| `snownee.lychee.item_burning.ItemBurningRecipe` | 物品实体燃烧后触发 | 世界实体型，不适合 Generic Delegate |
| `snownee.lychee.block_crushing.BlockCrushingRecipe` | 方块坠落/砸落到指定方块上 | 世界物理过程，需专用世界执行器 |
| `snownee.lychee.block_exploding.BlockExplodingRecipe` | 方块爆炸触发 | 高风险世界副作用，默认排除 |
| `snownee.lychee.item_exploding.ItemExplodingRecipe` | 物品爆炸触发 | 高风险世界副作用，默认排除 |
| `snownee.lychee.lightning_channeling.LightningChannelingRecipe` | 闪电劈中实体/物品后触发 | 实体和天气依赖，默认排除 |
| `snownee.lychee.dripstone_dripping.DripstoneRecipe` | 滴水石随机 tick 产出 | 长时间、随机、方块状态依赖，默认排除 |
| `snownee.lychee.random_block_ticking.RandomBlockTickingRecipe` | 方块随机 tick | 世界状态依赖，默认排除 |

此外，Lychee 还有通用的：

- `ContextualCondition` 条件系统：`and`、`or`、`not`、时间、天气、难度、位置、生物群系、方块、光照、实体生命值、潜行等。
- `PostAction` 动作系统：掉落物、放置方块、破坏方块、爆炸、伤害、经验、执行命令、延迟、修改输入物品 NBT、设置物品、随机选择、条件分支等。
- `maxRepeats` / `ChanceRecipe`：一次触发可能重复多次或按概率产出。
- `ghost`、`hideInRecipeViewer`、`comment`：JEI 显示元数据，不等于可自动化语义。

## “奇奇怪怪”的机制如何表现

### 1. 结果不一定来自 `assemble()`

Lychee 的基础 `LycheeRecipe` 有 `assemble()`，但很多配方的真实产物来自 `PostAction`：

```json
{
  "item_in": "minecraft:iron_ingot",
  "post": [
    { "type": "drop_item", "item": "minecraft:gold_nugget", "count": 2 }
  ]
}
```

对 RS 来说，不能只读取 `recipe.getResultItem()`。需要解析：

1. 输入路径；
2. `getPostActions()` / `getAllActions()`；
3. 每个动作的 `getItemOutputs()`；
4. 动作是否隐藏、是否随机、是否会修改输入；
5. 动作是否会造成方块、实体、玩家或世界副作用。

否则可能出现 JEI 看得到配方，但 RS 计划没有输出；或者 RS 只交付了一个模板输出，实际 Lychee 动作没有执行。

### 2. 输出可能是世界掉落物

`drop_item`、爆炸、方块破坏等动作通常会在世界中生成 `ItemEntity`，而不是写入一个机器输出槽。

表现形式是：

```text
RS 扣材料 -> 世界中生成 ItemEntity -> RSI 捕获指定掉落 -> 插回 RS
```

这必须复用项目已有的世界产物捕获契约：

- delegate 声明准确的预期产物；
- delegate 声明捕获区域；
- 获取 capture lease；
- 捕获不到真实实体时失败关闭；
- 不能直接用 recipe template 补发结果。

如果 Lychee 动作会生成多个不同产物、概率产物或产物数量不固定，则必须实现 `collectAllResults()`，不能只实现 `collectResult()`。

### 3. 输出可能修改输入物品

`DamageItem`、`NBTPatch`、`SetItem`、`PreventDefault` 等动作会改变输入物品，而不是单纯消耗输入。

典型表现：

```text
输入带 NBT 的工具 -> Lychee 修改工具耐久/标签 -> 输出仍是同一工具但 NBT 已改变
```

这类配方对 RS 的要求是：

- 材料匹配必须使用 `ItemStack.isSameItemSameTags`；
- 不能只按 `Item` 合并库存；
- 需要把真实输入栈放进 Lychee 上下文执行；
- 执行后收集修改后的真实栈；
- 执行失败时需要恢复输入，或者在提交前确保动作可回滚。

不能用 Generic Delegate 先扣掉一个单位模板，再用 `getResultItem()` 伪造输出。

### 4. 条件不是普通 Ingredient

Lychee 条件可能检查：

- 指定方块或方块状态；
- 方块上方、下方、落点方块；
- 维度、生物群系、结构、光照；
- 时间、天气、难度；
- 玩家潜行、实体生命值、坠落距离；
- 执行命令或自定义条件。

所以即使配方的 `getIngredients()` 能提取出物品，也不代表当前 RS 网络环境满足配方。

计划阶段至少要显示或标记：

```text
需要世界条件：是
需要绑定位置：是/否
条件可由 RSI 验证：是/否
```

不能让解析器把一个依赖“雷雨天气 + 指定方块”的配方当成永久可用的普通合成节点。

### 5. 随机和重复次数会改变产量

Lychee 支持 `ChanceRecipe`、`maxRepeats`、`random_select` 等机制。

表现形式可能是：

```text
一次消耗 -> 0 个、1 个或多个结果
```

这与当前 DAG 的确定性物料守恒模型不兼容，除非为该配方声明明确的概率/产量策略。默认处理建议：

- 不把随机输出加入自动合成候选；
- 或仅允许能证明“确定性固定输出”的动作组合；
- `hasDeterministicPrimaryOutput()` 对随机 Lychee 配方返回 `false`；
- 计划界面显示“随机产出，不支持自动合成”，而不是错误显示固定数量。

## 与当前 rs-integration 的关系

当前项目的通用路径大致是：

```text
RecipeManager
  -> RecipeIndex
  -> ModRecipeHandler
  -> IngredientSpec / result
  -> GenericBatchDelegate
  -> ExtractionLedger
  -> RS 网络产物
```

Lychee 可以接入 `RecipeIndex`，但只能在 `ModRecipeHandler` 层加一个**严格过滤的 Lychee Handler**，不能仅靠反射自动扫描所有 Lychee 配方。

当前通用实现的几个关键限制：

1. `GenericBatchDelegate` 会提取 `IngredientSpec`，然后直接把配方结果作为完成结果收集；它不会执行 Lychee 的世界上下文和 `PostAction`。
2. `RecipeIndex` 会把 handler 返回的结果作为可生产物品建立 DAG 索引；如果把随机/世界副作用配方全部加入，会产生错误的递归计划。
3. 当前的二次产物探测主要面向 `getRemainingItems()`、`getByproducts()`、`getRollResults()`、`getOutputs()` 等常规接口，不能替代 Lychee 的动作解释器。
4. 世界掉落产物必须走 capture contract；不能让 Generic Delegate 只返回 `getResultItem()`。
5. Lychee 的上下文通常不是普通 `Container`，需要 `LycheeContext` 或其子类，不能用空容器调用 `assemble()` 伪造执行。

## 推荐的兼容分层

### A. 第一阶段：安全的物品型 Lychee

建议纳入：

- `ItemShapelessRecipe`；
- `AnvilCraftingRecipe`，如果输入、输出和耐久/等级成本能够准确执行；
- `ShapedCraftingRecipe`，如果动作只涉及确定性物品输出；
- 仅包含以下安全动作的配方：
  - `SetItem`；
  - `DamageItem`；
  - `NBTPatch`；
  - 明确的物品输出动作；
  - 不依赖世界/实体/玩家的 `CompoundAction`。

必要过滤：

- 必须存在可解析的物品输入；
- 必须存在确定性物品输出；
- 不含 `ChanceRecipe`、随机选择、延迟、命令、爆炸、方块变更；
- 条件为空，或条件可以在无世界副作用的情况下证明恒真；
- 输出数量来自真实 ItemStack，不提前单位化；
- NBT 输出按完整标签匹配。

表现形式：

```text
JEI 配方 -> RSI 计划中显示为 Lychee 物品配方
RS 网络扣输入 -> 专用 Lychee 执行器构造上下文 -> 执行动作
-> 收集真实 ItemStack -> 插回 RS
```

### B. 第二阶段：绑定 Lychee 世界工作站

对于未能静态虚拟化的 `BlockInteractingRecipe`、`ItemInsideRecipe`、部分 `BlockCrushingRecipe`，可以设计一个专用的 Lychee World Delegate：

- 绑定一个明确的方块坐标；
- 检查区块加载和方块状态；
- 将输入物品以 Lychee 需要的方式投放到指定位置；
- 等待 tick / marker / 完成事件；
- 捕获真实世界产物；
- 失败时清理输入和动作残留。

这类配方不能使用“任意绑定机器”的通用语义。JEI `+` 点击后需要选择或绑定 Lychee 执行位置，且同一位置要有 machine scope / capture lease。

### C. 默认排除

以下类型不建议进入 RS 自动合成：

- `BlockExplodingRecipe`；
- `ItemExplodingRecipe`；
- `LightningChannelingRecipe`；
- `RandomBlockTickingRecipe`；
- `DripstoneRecipe`；
- 包含 `Execute` 命令动作；
- 包含 `Explode`、`Hurt`、`Break`、`PlaceBlock` 等世界副作用动作；
- 含自定义条件/自定义动作但 RSI 无法验证其语义；
- 含延迟或玩家/实体状态条件的配方；
- 随机数量、随机选择或不确定输出的配方。

这些配方仍然可以被 Lychee 自身和 JEI 展示，但在 RSI 中应标记为“不支持自动合成”，而不是静默索引成普通产物。

## 推荐实现方案

### 1. 新增 `LycheeRecipeHandler`

建议路径：

```text
src/main/java/com/huanghuang/rsintegration/mods/lychee/LycheeRecipeHandler.java
```

职责：

- 使用 `instanceof` 判断 Lychee 公开类型；
- 提取 `ItemShapelessRecipe` / `ItemAndBlockRecipe` 的输入；
- 从 `getAllActions()` 收集物品输出；
- 计算 `LycheeSupportLevel`：`SAFE_ITEM`、`WORLD_CONTEXT`、`UNSUPPORTED`；
- 对随机、延迟、命令和世界动作返回不可自动化；
- 对 NBT 敏感配方返回正确的 NBT 标记；
- 不用“扫描第一个 ItemStack 字段”猜输出。

建议不要把 Lychee 类名硬编码在主类中。可使用已有 optional-mod 模块注册方式，只有检测到 `lychee` 时注册 handler。

### 2. 新增专用 Delegate，而不是复用 Generic Delegate

建议路径：

```text
src/main/java/com/huanghuang/rsintegration/mods/lychee/LycheeBatchDelegate.java
```

至少需要处理：

- Lychee context 的构造；
- 真实输入栈注入；
- `tickOrApply()` / `applyPostActions()` 执行；
- 真实输出收集；
- 世界掉落 capture；
- 多产物 `collectAllResults()`；
- cancel/failure 清理；
- 共享 `ExtractionLedger` 下不重复扣料或退款。

第一阶段如果只支持纯物品动作，也可以把 delegate 做成“逻辑执行器”，但仍然不能简单返回 `getResultItem()`，必须执行 Lychee 动作并收集实际结果。

### 3. 配方索引增加显式能力过滤

`RecipeIndex` 中建议加入类似判断：

```java
if (handler instanceof LycheeRecipeHandler lychee
        && !lychee.isSafeForAutoCraft(recipe)) {
    skippedUnsupported++;
    continue;
}
```

更好的长期方案是把能力写入 recipe entry：

```java
enum RecipeAutomationLevel {
    GENERIC,
    SAFE_ITEM,
    WORLD_CONTEXT,
    UNSUPPORTED
}
```

这样计划界面可以显示原因，调试命令也能统计 Lychee 配方覆盖率，而不是把所有跳过都归为 unknown。

### 4. 明确产物守恒策略

Lychee 支持动作链，动作链可能：

- 消耗多个输入；
- 修改某个输入；
- 生成多个输出；
- 生成世界方块或实体；
- 失败后部分执行。

因此建议执行时按以下顺序：

```text
无副作用预检查
-> 预留/提交输入
-> 创建 Lychee context
-> 执行动作
-> 收集所有真实物品产物
-> 验证至少满足预期输出
-> 插回 RS
```

如果动作链不可回滚，不能在普通 DAG 并发路径中放行；应保持 exclusive，或者完全排除。

## `ItemInsideRecipe` 磁盘内虚拟合成专项方案

本节以整合包中的下列配方为首个目标：

```text
crafttweaker:avaritia.diamond_lattice.1
```

这里采用的不是“自动把物品丢进世界方块”，而是**受限的配方投影**：把能够证明为确定性物品转换的 `ItemInsideRecipe` 映射成 RS 内部逻辑配方。材料、环境凭证和产物都走 RS 存储磁盘与外层 DAG，不生成 `ItemEntity`，也不需要绑定世界位置。

### 1. 已确认的实际配方

运行日志和 `scripts/avaritia.zs` 已确认：

```text
Recipe type: lychee:item_inside
Recipe class: snownee.lychee.item_inside.ItemInsideRecipe
Recipe id: crafttweaker:avaritia.diamond_lattice.1
getResultItem(): minecraft:air
```

脚本中的钻石晶格配方具有统一结构：

```json
{
  "type": "lychee:item_inside",
  "item_in": { "item": "mowziesmobs:wrought_helmet" },
  "block_in": { "blocks": ["minecraft:powder_snow"] },
  "post": {
    "type": "drop_item",
    "item": "avaritia:diamond_lattice",
    "count": 1
  }
}
```

已发现的配方为 `.1`、`.2`、`.3`、`.4`、`.6` 到 `.12`；脚本中没有 `.5`，实现和诊断都不能自行补出 `.5`。

| 配方后缀 | 被消耗的 `item_in` |
|---|---|
| `.1` | `mowziesmobs:wrought_helmet` |
| `.2` | `mowziesmobs:ice_crystal` |
| `.3` | `mowziesmobs:sol_visage` |
| `.4` | `mowziesmobs:wrought_axe` |
| `.6` | `mowziesmobs:grant_suns_blessing` |
| `.7` | `constructionwand:core_destruction` |
| `.8` | `constructionwand:core_angel` |
| `.9` | `mokels_witch_boss:witch_staff` |
| `.10` | `mokels_witch_boss:flask_of_healing` |
| `.11` | `mokels_boss_mantyd:mantyd_scythe` |
| `.12` | `mokels_boss_mantyd:mantydhelmet_helmet` |

它们都要求 `minecraft:powder_snow`，都只执行固定的 `drop_item`，都产出 1 个 `avaritia:diamond_lattice`。JSON 没有时间、概率、条件或其他世界动作。

`getResultItem() == air` 只表示产物存放在 Lychee 的 post action 中。索引器需要解析动作，而不是把 air 当成无产物。

### 2. 玩家语义

该配方在 RSI 中显示为：

```text
消耗：对应的 item_in x1
共振盘催化剂：与 block_in 对应的桶 x1（不消耗）
产出：avaritia:diamond_lattice x1
```

对应桶直接放在现有的共振存储盘普通槽位中。第一版不新增方块、不增加可见的催化剂槽，也不改变共振背包的外观。

当前内置并经过结构审核的映射为：

| `block_in` | 共振盘催化剂 | 额外结构要求 |
|---|---|---|
| `minecraft:powder_snow` | `minecraft:powder_snow_bucket` | 无 state/NBT/tag 约束 |
| `locusazzurro_icaruswings:greek_fire` | `locusazzurro_icaruswings:greek_fire_bucket` | 必须为源方块 `level=0` |
| `embers:dwarven_oil_block` | `embers:dwarven_oil_bucket` | 必须为源方块 `level=0` |
| `deep_aether:poison` | `deep_aether:poison_bucket` | 必须为源方块 `level=0` |

Lychee 虚拟配方把细雪桶视为“共振盘催化剂”，而不是普通 RS 材料：

- `LycheeVirtualRecipeHandler` 直接读取当前网络的共振存储盘；
- 只有共振盘中存在匹配的催化剂时，JEI/RSI 才显示自动合成按钮；
- 催化剂不从网络中提取、不进入 virtualInventory、DAG 材料账本或 reservation，也不按产量扣除；
- 共振盘只有一块时，催化剂来源天然明确；
- 玩家取出细雪桶后按钮消失，已经排队的操作在 commit 前重新校验并中止。

这样可以复用现有的共振盘、共振背包和网络归属，不需要新增世界方块或破坏 GUI 美感；合成时仍不需要反应盘、控制方块、绑定坐标或真实细雪方块。

一次批量合成的逻辑流程：

```text
外层 DAG 解析 item_in 的全部上游依赖
-> 检查共振存储盘中存在 1 个细雪桶
-> 预留本批次需要消耗的 item_in
-> commit 前再次检查细雪桶仍然存在
-> 提交逻辑操作
-> 将固定 DropItem 产物发布到 virtualInventory
-> 最终产物写回 RS 存储
```

细雪在原配方中只是持续存在的环境条件，不会被 Lychee 消耗。因此细雪桶也必须是可复用催化剂，不能每合成一个钻石晶格就消耗一个细雪桶或产生一个空桶。将它独立于普通材料提取流程，还能避免每个 DAG 节点都重新扫描整个 RS 网络库存。

共振盘催化剂是服务端执行守卫，不是需要占有的材料：

- 共振盘只能关联一个 RS 网络；
- 计划候选过滤时检查一次，真正预留输入前再检查一次；
- commit 前做最后一次权威检查，检查失败时释放输入 reservation，不产生输出；
- 虚拟转换在同一个服务端执行段内完成，检查通过后不等待世界 tick；
- 玩家可以随时取出催化剂，不需要槽锁或盘锁；
- 共振盘被移除、网络解绑或维度不可用时，虚拟配方进入不可用状态，不回退到世界投放。

不建议扫描任意附近箱子、抽屉或漏斗作为催化剂来源。任意容器会引入区块加载、外部管道抢占、权限和多网络归属问题，反而比读取已有共振盘更难保证正确性。

共振盘的普通 `getStacks()` 仍可以让 RS 看见细雪桶，但虚拟 Lychee handler 不能把它加入普通 `IngredientSpec` 或普通材料提取流程；它必须通过共振盘专用只读检查来判断催化剂是否存在。这样不会新增槽位，也不会让 delegate 尝试从普通 RS 网络提取共振盘中的桶。

### 3. 配置白名单

白名单位于 RSI 的 common 配置文件中：

```toml
enableLychee = true
lycheeRecipeAllowlist = [
  "crafttweaker:avaritia.diamond_lattice.1",
  "crafttweaker:avaritia.diamond_lattice.2",
  "crafttweaker:avaritia.diamond_lattice.3",
  "crafttweaker:avaritia.diamond_lattice.4",
  "crafttweaker:avaritia.diamond_lattice.6",
  "crafttweaker:avaritia.diamond_lattice.7",
  "crafttweaker:avaritia.diamond_lattice.8",
  "crafttweaker:avaritia.diamond_lattice.9",
  "crafttweaker:avaritia.diamond_lattice.10",
  "crafttweaker:avaritia.diamond_lattice.11",
  "crafttweaker:avaritia.diamond_lattice.12"
]
```

默认列表同时包含已审核的细雪、希腊火、烬铸原液和 Deep Aether 毒液配方；现有实例需要把新增 ID 合并进原列表。Deep Aether 的这三张配方本身仍声明为 `lychee:item_inside`，因此复用同一虚拟执行路径，只是 `block_in` 和桶来自 Deep Aether。列表为空时关闭所有 Lychee 虚拟配方。配置只能收窄允许的配方 ID，不能放宽代码中的安全校验（配方类型、基底映射与源方块状态、零延时、无额外条件和唯一 `drop_item`）。

### 3. 这不是通用的 Lychee 模拟器

虚拟合成只有在 RSI 能静态证明“世界条件只是凭证，动作等价于固定物品转换”时才允许。第一版必须同时满足：

1. 配方类是 `ItemInsideRecipe`；
2. `block_in` 是单一、可映射的简单方块谓词；
3. 所有 `item_in` 都能转换成有限、可预留的 `IngredientSpec`；
4. 没有位置、维度、生物群系、天气、亮度、实体状态等上下文条件；
5. 没有 `chance`、`random_select` 或其他随机分支；
6. 没有 `delay`，且 `time` 为立即完成语义；
7. post action 仅包含固定物品输出，第一版只放行确定性的 `DropItem`；
8. 没有 `execute`、`custom`、`explode`、`place_block`、`cycle_state_property`、伤害、经验或实体动作；
9. 没有改变默认输入消耗语义的 `prevent_default`、`set_item`、NBT patch 等动作；
10. 能枚举全部主产物和副产物，并能验证数量大于零。

任意一项无法证明时必须 fail closed。配方仍可由玩家按 Lychee 原方式手动完成，但不会出现在 RSI 自动合成候选中。

这个限制使虚拟路径不需要伪造 `LycheeContext`，也不会在没有世界位置的情况下错误执行命令、爆炸或方块修改。

### 4. 方块到共振盘催化剂物品的映射

新增显式 `LycheeVirtualCatalystRegistry`，只接受白名单映射，不根据方块名称猜桶或容器。映射的右侧物品存放在共振存储盘普通槽位：

```text
minecraft:powder_snow -> minecraft:powder_snow_bucket
minecraft:water       -> minecraft:water_bucket
minecraft:lava        -> minecraft:lava_bucket
```

第一版实际启用并验收 `powder_snow -> powder_snow_bucket`。水和熔岩可以使用同一机制，但必须在对应配方通过动作安全检查后才放行。

模组流体或普通方块不能自动套用 `*_bucket` 规则。它们需要显式注册：

```text
block predicate
-> catalyst Ingredient
-> source = RESONANCE_DISK
-> consumption policy = REUSABLE
```

若方块谓词包含多个候选方块，只有所有候选都存在明确且语义等价的催化剂映射时才能形成一个替代 Ingredient；否则拒绝虚拟化。

### 5. 数据模型

建议在加载期生成不可变描述对象：

```java
record LycheeVirtualRecipeDescriptor(
        ResourceLocation recipeId,
        List<IngredientSpec> consumedInputs,
        CatalystRequirement catalystRequirement,
        List<ItemStack> fixedOutputs,
        String validationFingerprint
) {}

record CatalystRequirement(
        Ingredient catalyst,
        CatalystSource source,
        ConsumptionPolicy consumption
) {}
```

第一版的 `CatalystSource` 只有 `RESONANCE_DISK`，`ConsumptionPolicy` 固定为 `REUSABLE_PRESENCE`。不要把催化剂混入普通 `IngredientSpec`，否则批量规划会错误地按产量倍增细雪桶需求，并错误进入普通 RS 提取路径。

`validationFingerprint` 至少覆盖：

- 配方 ID 和 `ItemInsideRecipe` 类型；
- 输入 Ingredient 的稳定表示；
- 完整 block predicate；
- post action 类型、物品、数量和顺序；
- Lychee 版本和当前 recipe reload revision。

计划生成后如果数据包重载导致 fingerprint 改变，旧计划必须失效并重新解析，不能继续使用旧的固定输出。

### 6. 模块边界

建议新增：

```text
mods/lychee/LycheeRSModule.java
mods/lychee/LycheeReflection.java
mods/lychee/LycheeVirtualCatalystRegistry.java
mods/lychee/LycheeVirtualRecipeDescriptor.java
mods/lychee/LycheeVirtualRecipeHandler.java
mods/lychee/LycheeVirtualBatchDelegate.java
```

职责划分：

| 类 | 职责 |
|---|---|
| `LycheeRSModule` | Lychee 存在时注册 ModType、handler 和 JEI 映射 |
| `LycheeReflection` | 集中读取 Lychee 配方、block predicate 与 post action，启动时验证反射契约 |
| `LycheeVirtualCatalystRegistry` | 保存方块谓词到共振盘催化剂物品的显式映射 |
| `LycheeVirtualRecipeHandler` | 验证安全子集、提取真实输入和固定输出、生成 descriptor |
| `LycheeVirtualBatchDelegate` | 执行无机器的事务化逻辑合成，处理催化剂归还和批量产出 |

Lychee 是可选模组。公共核心类不能在 Lychee 缺失时直接链接 `snownee.lychee.*`；直接 API 调用应限制在仅于模组存在时加载的集成边界内，或者集中反射并做 contract validation。

不能把原始 `ItemInsideRecipe` 直接交给 `GenericBatchDelegate`：它看到的普通结果仍是 air，而且不知道哪个 Ingredient 是可复用的世界条件。可以复用 Generic Delegate 的无物理机器事务模式，但必须由 Lychee 专用 handler/delegate 提供输出与催化剂语义。

### 7. 配方索引

当前 `RecipeIndex` 在 handler 提取结果为空后会执行 `skippedEmptyResult++`。Lychee handler 必须在这个判断前从已验证的 `DropItem` 动作返回固定产物。

索引流程应为：

```text
发现 ItemInsideRecipe
-> LycheeVirtualRecipeHandler.validate(recipe)
-> 解析 consumedInputs
-> 用 block predicate 查询 reusableCatalyst
-> 解析 fixedOutputs
-> 全部通过：以 fixedOutputs 建立 RecipeIndex.Entry
-> 任一失败：记录明确拒绝原因，不进入索引
```

拒绝原因至少区分：

```text
UNMAPPED_BLOCK_CATALYST
CONTEXT_DEPENDENT_CONDITION
NON_DETERMINISTIC_ACTION
WORLD_SIDE_EFFECT
UNSUPPORTED_INPUT_MUTATION
EMPTY_OR_AMBIGUOUS_OUTPUT
UNSUPPORTED_TIME_SEMANTICS
```

不能仅按 `crafttweaker:avaritia.diamond_lattice.*` 名称硬编码输出。白名单可以限制允许范围，但输入和产物仍必须来自当前运行时配方并通过结构检查。

### 8. 外层 DAG 和催化剂守卫

descriptor 只为真正消耗的 `item_in` 产生材料 demand；细雪桶是执行守卫，不是 DAG 材料：

```text
item_in            -> MaterialDemand(CONSUMED, PER_OPERATION)
powder_snow_bucket -> ExecutionGuard(RESONANCE_DISK_PRESENT, REUSABLE_PRESENCE)
```

这意味着批量制作 64 个钻石晶格时：

- 对应的 64 个 `item_in` 会被消耗；
- 共振盘中只需要存在 1 个细雪桶；
- 细雪桶不会进入 virtualInventory，也不会被 delegate 从共振盘提取；
- 不产生 64 个空桶，也不要求 64 个细雪桶。

如果 `item_in` 本身需要多步合成，其 producer 必须在规划阶段并入同一个外层 DAG。delegate 不得启动嵌套 `AsyncCraftChain`，也不得用 private-ledger 再向 RS 提取一次上游产物。

催化剂缺失时不创建这条候选计划，也不尝试在 delegate 内递归合成细雪桶。客户端根据服务端同步的共振盘催化剂快照隐藏 RSI 按钮；服务端仍在执行前重新检查，不能只信任客户端状态。

### 9. 事务和结算

建议状态机：

```text
PREPARING
-> CATALYST_GUARD_PASSED
-> RESERVED_INPUTS
-> CATALYST_REVALIDATED
-> COMMITTED
-> OUTPUTS_PUBLISHED
-> SETTLED
```

执行要求：

1. admission 只为真正消耗的输入创建 reservation；
2. 输入预留前和 commit 前都检查共振盘催化剂；
3. commit 前检查失败，输入 reservation 原样释放；
4. commit 后只根据 descriptor 生成固定输出；
5. 催化剂不创建 ledger token，不作为产物或 remainder 返回；
6. 固定产物成功进入 `virtualInventory` 后才能 settle；
7. 输入 reservation 生命周期保持 `RESERVED -> COMMITTED -> SETTLED`，每个 token 只结算一次；
8. 取消、掉线、服务器停止和异常恢复都必须保持输入和产物总量守恒，且绝不修改共振盘中的桶。

逻辑 delegate 没有不可回滚的世界副作用，因此在 commit 后发生框架异常时，可以用已提交输入重建固定输出恢复记录；共振盘催化剂始终只读，不参与恢复。但同一个 operation ID 只能发布一次，避免重试刷物。

### 10. JEI 和玩家界面

为以下 JEI UID 注册 RSI 入口：

```text
lychee:item_inside/minecraft/default -> lychee_virtual_item_inside
```

点击合成按钮后的流程：

```text
读取客户端同步的共振盘催化剂快照
-> 没有匹配催化剂：不绘制 RSI 按钮
-> 有匹配催化剂：绘制 RSI 按钮
-> 玩家点击后由服务端读取当前 Lychee recipe
-> 查找已验证 descriptor
-> 显示普通合成计划
-> 将细雪桶标记为“催化剂，不消耗”
-> 直接提交外层 DAG
```

这个入口不查询 `AltarBindingRegistry`，不弹出机器选择，也不要求附近存在任何方块。

共振盘不存在或盘中没有催化剂时，默认不绘制 RSI 自动合成按钮。若按钮显示后玩家立即取出了桶，服务端拒绝提交并提示“共振存储盘中缺少细雪桶”，且不能预扣其他输入。

催化剂快照不应在每一帧扫描共振盘。服务端在玩家网络变化、共振背包内容变化、磁盘驱动器变化或登录/重连时生成一个紧凑的 catalyst bitset/revision 并同步客户端；客户端只读取缓存决定按钮是否可见。

被安全规则拒绝时显示实际原因，例如“包含随机 Lychee 动作”或“该世界方块没有共振盘催化剂映射”，不能回退到错误的世界绑定提示。

### 11. 配置

建议配置：

```text
enableLycheeVirtualItemInside = true
lycheeVirtualRecipeAllowlist = ["crafttweaker:avaritia.diamond_lattice.*"]
lycheeVirtualRecipeDenylist = []
lycheeVirtualCatalystMappings = [
  "minecraft:powder_snow=minecraft:powder_snow_bucket"
]
lycheeCatalystSource = "RESONANCE_DISK"
```

安全结构检查始终强制执行。allowlist 只表示“允许尝试解析”，不能绕过随机动作、世界副作用、未知催化剂或不确定输出检查。

首版建议默认只开放已验收的钻石晶格命名范围，运行时仍逐个检查现有的 11 张配方；后续再考虑把默认范围扩大到所有满足安全子集的 `ItemInsideRecipe`。

### 12. 实施顺序

#### 阶段 1：只读诊断

- 输出所有 `ItemInsideRecipe` 的输入、block predicate、time、conditions 和 post actions；
- 输出 `.1` 到 `.12` 的结构化差异，并明确报告 `.5` 不存在；
- 为每张配方输出 `VIRTUAL_SAFE` 或具体拒绝原因。

#### 阶段 2：描述器和索引

- 实现细雪到细雪桶的显式催化剂映射和共振盘只读检查；
- 从固定 `DropItem` 提取结果，绕过 air 结果缺口；
- 让 11 张钻石晶格配方按真实输入进入 `RecipeIndex`。

#### 阶段 3：虚拟 delegate

- 接入 `CatalystRequirement(RESONANCE_DISK, REUSABLE_PRESENCE)`，不创建材料 demand；
- 完成外层 DAG checkout、commit 前催化剂复查、固定输出发布和 exactly-once 结算；
- 禁止绑定查找、世界实体创建和嵌套合成链。

#### 阶段 4：JEI 与批量验收

- 注册 `lychee:item_inside/minecraft/default` 的合成入口；
- 同步共振盘催化剂 bitset/revision，并按存在状态显示或隐藏按钮；
- 验证单次、批量、递归中间步骤、取消和恢复。

### 13. 专项验收标准

1. RS 中有 `.1` 的输入，共振存储盘中有 1 个细雪桶时，可得到 1 个钻石晶格；
2. 合成后输入减少 1，钻石晶格增加 1，共振盘中的细雪桶数量不变；
3. 整个过程不创建世界 `ItemEntity`，不要求绑定或加载世界坐标；
4. 缺少细雪桶时不绘制 RSI 自动合成按钮，且不预扣其他输入；
5. 批量合成不会按产量倍增细雪桶需求，也不会从共振盘取出细雪桶；
6. `item_in` 需要多步合成时，依赖并入外层 DAG，不启动嵌套链；
7. private-ledger 不会二次提取已由上游节点交付的材料；
8. `.1`、`.2`、`.3`、`.4`、`.6` 到 `.12` 都按各自真实输入建立候选；
9. 不存在的 `.5` 不会出现在索引、计划或日志成功列表中；
10. 含概率、命令、爆炸、方块修改或未知 post action 的配方被拒绝；
11. 数据包重载改变配方后，旧 descriptor 失效；
12. 取消、异常和服务器停止后输入和产物均不复制、不丢失，共振盘内容不被修改；
13. graph reservation 始终按 `RESERVED -> COMMITTED -> SETTLED` exactly once；
14. Lychee 未安装时集成模块不加载，RSI 其他功能正常启动。

## 计划界面和玩家可见表现

建议在计划界面给 Lychee 配方增加以下信息：

| 状态 | 显示建议 |
|---|---|
| 纯物品、确定性 | `Lychee 物品配方`，正常显示输入/输出 |
| `ItemInsideRecipe` + 已映射方块 + 确定性输出 | `Lychee 虚拟配方`，单列显示“不消耗催化剂” |
| 需要真实方块/位置且不能虚拟化 | `需要 Lychee 世界条件`；当前不显示自动合成按钮，未来 World Delegate 才显示绑定位置 |
| 随机产物 | `随机产出，不支持自动合成` |
| 有世界副作用 | `包含世界动作，不支持自动合成` |
| 自定义条件/动作 | `无法验证自定义 Lychee 条件` |
| 仅 JEI 展示 | 不显示 RSI `+` 自动合成按钮，保留 Lychee 原配方查看 |

不要把“配方存在”直接等同于“RS 可以自动执行”。Lychee 的 `ghost` 和 `hideInRecipeViewer` 也应只影响显示，不应绕过安全能力判断。

## 验收测试建议

### 必测正例

1. `diamond_lattice.1` 使用共振存储盘中的细雪桶完成，细雪桶不消耗且不创建世界实体。
2. 11 张现存钻石晶格配方都按各自输入建立候选，不生成不存在的 `.5`。
3. 批量制作只要求共振盘中存在一个细雪桶，输入和输出按次数增长。
4. 一个纯 `ItemShapelessRecipe`，固定输入、固定 `drop_item` 输出。
5. 一个带多个输出的安全动作链，确认所有产物都进入 RS。
6. 一个带 crafting remainder 的配方，确认 remainder 不重复计入。
7. 一个有多个候选配方的物品，确认计划数量和候选切换正确。

### 必测负例

1. `ChanceRecipe` 不进入自动合成候选。
2. `maxRepeats` 不被错误当成固定一次产量。
3. `Execute` / `Explode` / `PlaceBlock` / `Break` 动作被拒绝。
4. `ItemInsideRecipe` 的方块没有催化剂映射时不能进入磁盘虚拟路径。
5. 需要真实位置、环境条件或未知输入变换的配方不能伪装成虚拟配方。
6. cancel、玩家断线、数据包重载和动作中途失败后不刷物、不吞物，也不修改共振盘催化剂。
7. Lychee 不存在时，主模组仍能正常启动和编译加载。

## 最终建议

Lychee **值得做兼容，但应作为一个“动作驱动配方系统”单独接入**。第一版的合理范围是：

```text
纯物品输入
+ 确定性 ItemStack 输出
+ 无世界副作用
+ 无随机/延迟/命令
+ 或者能静态投影成“消耗材料 + 共振盘催化剂 + 固定输出”
```

钻石晶格这批 `ItemInsideRecipe` 应优先走磁盘内虚拟路径：细雪映射为共振存储盘中的不消耗细雪桶，不绑定机器、不生成世界实体。没有催化剂时不绘制 RSI 自动合成按钮；有催化剂时，它们可以稳定地表现为 RS 合成计划中的普通中间步骤。

需要真实世界、实体、随机、爆炸、闪电和命令的其他配方，不应由当前 Generic Delegate 猜测执行；它们要么等待未来的专用 World Delegate，要么保持 Lychee 原生交互。
