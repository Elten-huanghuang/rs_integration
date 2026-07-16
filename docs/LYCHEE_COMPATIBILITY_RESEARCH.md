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
| `snownee.lychee.item_inside.ItemInsideRecipe` | 物品放入指定方块/容器中，可能等待一段时间 | 需要实体/方块状态和 tick 执行 |
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

对于 `BlockInteractingRecipe`、`ItemInsideRecipe`、部分 `BlockCrushingRecipe`，可以设计一个专用的 Lychee World Delegate：

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

## 计划界面和玩家可见表现

建议在计划界面给 Lychee 配方增加以下信息：

| 状态 | 显示建议 |
|---|---|
| 纯物品、确定性 | `Lychee 物品配方`，正常显示输入/输出 |
| 需要方块/位置 | `需要 Lychee 世界条件`，显示绑定位置 |
| 随机产物 | `随机产出，不支持自动合成` |
| 有世界副作用 | `包含世界动作，不支持自动合成` |
| 自定义条件/动作 | `无法验证自定义 Lychee 条件` |
| 仅 JEI 展示 | 不显示 RSI `+` 自动合成按钮，保留 Lychee 原配方查看 |

不要把“配方存在”直接等同于“RS 可以自动执行”。Lychee 的 `ghost` 和 `hideInRecipeViewer` 也应只影响显示，不应绕过安全能力判断。

## 验收测试建议

### 必测正例

1. 一个纯 `ItemShapelessRecipe`，固定输入、固定 `drop_item` 输出。
2. 一个带多个输出的动作链，确认所有产物都进入 RS。
3. 一个修改输入 NBT 的配方，确认输入标签不丢失，输出不是模板栈。
4. 一个带 crafting remainder 的配方，确认 remainder 不重复计入。
5. 一个有多个候选配方的物品，确认计划数量和候选切换正确。

### 必测负例

1. `ChanceRecipe` 不进入自动合成候选。
2. `maxRepeats` 不被错误当成固定一次产量。
3. `Execute` / `Explode` / `PlaceBlock` / `Break` 动作被拒绝。
4. 需要方块位置但未绑定时不能启动。
5. 世界掉落被玩家或磁铁提前取走时，RS 不补发模板产物。
6. cancel、机器卸载、玩家断线、动作中途失败后不刷物、不吞物。
7. Lychee 不存在时，主模组仍能正常启动和编译加载。

## 最终建议

Lychee **值得做兼容，但应作为一个“动作驱动配方系统”单独接入**。第一版的合理范围是：

```text
纯物品输入
+ 确定性 ItemStack 输出
+ 无世界副作用
+ 无随机/延迟/命令
+ 可在 Lychee context 中安全执行
```

这部分可以稳定地表现为 RS 合成计划中的普通中间步骤。需要世界、实体、随机、爆炸、闪电和命令的配方，不应由当前 Generic Delegate 猜测执行；它们要么等专用 World Delegate，要么保持 Lychee 原生交互。
