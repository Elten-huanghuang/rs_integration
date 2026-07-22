# 原版炼药台与 Iron's Spellbooks 炼金锅递归合成计划

## 1. 范围

本文覆盖：

- `minecraft:brewing_stand` 原版炼药台；
- `irons_spellbooks:alchemist_cauldron` 炼金锅；
- 两者与 Goety 女巫坩埚之间可共享的药剂身份、递归规划和事务基础设施。

三者不能共用同一个执行 delegate：

| 系统 | 工艺模型 | 核心状态 |
|---|---|---|
| 原版炼药台 | 3 个容器 + 单个试剂的逐级转换 | 物品槽、烈焰粉燃料、brewTime |
| Iron 炼金锅 | 物品试剂 + 流体输入 -> 流体结果/物品副产物 | Forge FluidTank、多输入槽、cooktimes |
| Goety 女巫坩埚 | 严格有序的容量/效果/增强状态机 | 模式、容量、效果集合、灵魂成本 |

共享层只负责“目标身份、候选转换图、递归需求和精确输出验证”。机器交互、恢复和并发策略必须分开。

## 2. Iron's Spellbooks 3.15.4 研究结果

本节依据运行版本 `irons_spellbooks-1.20.1-3.15.4.jar` 的 JSON 与字节码。

### 2.1 Datapack 配方族

存在三种正式 Recipe 类型：

1. `alchemist_cauldron_brew`
   - 输入流体 `fluidIn`；
   - 物品试剂 `reagent`；
   - 零到多个输出流体 `results`；
   - 可选物品副产物 `byproduct`。
2. `alchemist_cauldron_fill`
   - 输入装有液体的物品；
   - 返回空容器；
   - 将指定 FluidStack 加入锅内；
   - `mustFitAll` 控制是否必须完整容纳。
3. `alchemist_cauldron_empty`
   - 输入空容器；
   - 从锅中抽取指定 FluidStack；
   - 返回装满后的物品。

示例升级链：

```text
1000 mB common_ink + copper_ingot
  -> 250 mB uncommon_ink

250 mB evasion_elixir + dragon_breath
  -> 250 mB greater_evasion_elixir
```

浸泡配方可以没有流体输出，只有副产物，例如：

```text
1000 mB blood + hogskin -> bloody_vellum
```

因此不能假设每一步都产生可继续递归的流体。

### 2.2 动态 JEI 配方

`AlchemistCauldronRecipeMaker` 组合三类来源：

- `getCauldronRecipes()`：正式 datapack 炼金锅配方；
- `getPotionRecipes()`：将原版 `PotionBrewing` 转换为锅内流体配方；
- `getScrollRecipes()`：按法术、等级和稀有度动态生成卷轴/墨水炼制。

如果只扫描 `RecipeManager`，会漏掉原版药水流体链和法术卷轴链。实现必须复刻 Maker 的数据来源，但输出 RSI 自己的稳定 recipe definition，不能依赖客户端 JEI 静态列表。

### 2.3 方块实体执行模型

`AlchemistCauldronTile` 提供：

- 多个 `inputItems`；
- 每槽独立 `cooktimes`；
- `AlchemistCauldronFluidHandler`；
- Forge `IFluidHandler` capability；
- `isValidInput`、`isBrewable`、`tryMeltInput`；
- `tryExecuteRecipeInteractions` 处理装入/取出容器。

执行前必须检查：锅正在沸腾、输入槽可用、流体种类和数量精确匹配、输出能完整放入。不能先消耗物品再发现输出流体与锅内剩余流体不兼容。

## 3. 原版炼药台模型

原版配方不在普通 `RecipeManager` 中，而由 `PotionBrewing` 的容器规则和 mix 列表维护：

- 容器转换：普通 -> 喷溅 -> 滞留；
- 药水转换：水瓶 -> 粗制/平凡/浓稠 -> 效果药水；
- 延时与增强：红石、萤石；
- 模组可通过 Forge brewing hooks 注册额外 mix。

规划器必须在服务端运行时读取完整 brewing registry，不能硬编码原版表，也不能只解析 JEI 页面。

一个操作最多同时处理三个瓶槽。需求数量大于 3 时拆成多个 operation；每个 operation 独立持有输入、试剂、燃料和输出。

## 4. 统一身份模型

### 4.1 ItemStack 身份

原版瓶装药水和 Goety 药酿使用物品 + NBT 身份。必须保留：

- Potion ID；
- CustomPotionEffects；
- 自定义颜色；
- Goety BrewEffect 数据；
- 形态与 modifier 字段。

### 4.2 FluidStack 身份

Iron 炼金锅使用 FluidStack。身份必须包含：

- Fluid registry ID；
- 完整 FluidStack NBT；
- 精确 mB 数量。

新增跨域材料键：

```java
sealed interface ProcessMaterial permits ItemMaterial, FluidMaterial {}
record ItemMaterial(ItemStack identity, int count) implements ProcessMaterial {}
record FluidMaterial(FluidStack identity, int amount) implements ProcessMaterial {}
```

不能把同一种流体的不同 NBT 合并，也不能把 250 mB 输出当作一个无单位“物品”。

## 5. 转换图与递归规划

新增 `ProcessConversionGraph`，节点为精确 ItemStack/FluidStack 身份，边为一个确定性机器 operation。

### 5.1 原版药水递归

目标 `strong_healing_splash` 的反向链示例：

```text
喷溅强效治疗药水
  <- 火药 + 强效治疗药水
  <- 萤石粉 + 治疗药水
  <- 闪烁的西瓜片 + 粗制药水
  <- 下界疣 + 水瓶
  <- 水桶/水源 + 玻璃瓶
```

每条边的容器余留、输出数量和燃料需求都进入 operation specification。

### 5.2 Iron 流体递归

目标“一个高级闪避药剂瓶”应展开为：

```text
高级闪避药剂物品
  <- empty recipe: 250 mB greater_evasion_elixir + 空瓶
  <- brew recipe: 250 mB evasion_elixir + 龙息
  <- evasion_elixir 的上游流体炼制/装入链
  <- 所有物品试剂的普通 DAG 合成
```

目标墨水与卷轴同理。中间流体应由同一锅连续使用，或通过显式容器化后跨机器传递。第一版建议优先选择“同锅连续链”，减少容器往返和流体损耗。

### 5.3 候选与环检测

- 一个目标可能同时存在原版炼药台与 Iron 炼金锅候选；
- resolver 按现有材料、绑定机器、步骤数和容器成本评分；
- Fluid fill/empty 的互逆边容易形成环，必须用目标数量 + 路径访问集合防环；
- 只允许产生净目标增量的边进入候选；
- 空瓶/药瓶等容器余留必须建模，不能被当作免费物品无限循环。

## 6. 原版炼药台实现

新增：

```text
mods/vanilla/brewing/
  VanillaBrewingCatalog.java
  VanillaBrewingRecipeDefinition.java
  BrewingStandBatchDelegate.java
  BrewingStandOperationJournal.java
```

### 6.1 绑定

新增 `vanilla_brewing_stand` ModType，只绑定 `minecraft:brewing_stand`。

### 6.2 准备校验

- 方块实体存在且不是外部玩家正在操作；
- 三个瓶槽中目标槽位空闲；
- 试剂槽和燃料槽可用；
- 烈焰粉燃料足够，或将烈焰粉计入需求；
- 使用运行时 brewing registry 模拟每个输入瓶，输出必须精确匹配计划。

### 6.3 执行与完成

- 一次放入最多 3 个同一转换边的输入；
- 放入试剂和必要燃料；
- 观察 `brewTime` 从启动到归零；
- 收集三个实际输出并逐个精确校验；
- 试剂容器余留作为 secondary output 回收；
- 任何槽位被外部改变时停止，不做盲目退款。

## 7. Iron 炼金锅实现

新增：

```text
mods/ironsspellbooks/alchemy/
  AlchemistCauldronCatalog.java
  AlchemistCauldronRecipeHandler.java
  AlchemistCauldronBatchDelegate.java
  AlchemistCauldronOperationJournal.java
  AlchemistFluidBroker.java
```

### 7.1 ModType 与绑定

新增 `irons_spellbooks_alchemist_cauldron`，只绑定 `irons_spellbooks:alchemist_cauldron`。

### 7.2 配方目录

服务端构建时合并：

1. Brew/Fill/Empty datapack recipes；
2. `PotionBrewing` 动态 mix；
3. Iron 法术注册表生成的卷轴配方；
4. `/reload` 与法术 registry 变化时失效缓存。

### 7.3 执行策略

推荐优先调用原生方块实体入口：

- Fill/Empty 使用 `tryExecuteRecipeInteractions`；
- Brew 使用输入槽和原生 tick；
- 流体读写通过 `IFluidHandler` 做 SIMULATE/PERFORM 两阶段校验；
- 不直接覆写 `fluidInventory` NBT。

对于同锅连续流体链，operation 可以持有多条 recipe edge，但每条完成后都要建立结算点。中途失败时只能退款尚未进入锅的材料；锅内真实流体和已生成副产物按实际状态交付。

## 8. 流体与 RS 网络边界

需要明确两种部署：

1. RS 网络有流体存储：直接预留与交付 FluidStack；
2. 只有物品存储：通过已注册 Fill/Empty recipe 使用桶、瓶和墨水瓶容器化。

第一版若现有 graph broker 仅支持 ItemStack，建议不要伪造流体物品。可先实现同锅连续链，并要求起始/最终流体都有合法容器 recipe；完整 FluidMaterial broker 作为后续基础设施升级。

## 9. 事务与防吞物品

两类机器均需要 operation journal。

### 9.1 炼药台 journal

- 每个瓶槽的启动前快照；
- 试剂与燃料快照；
- brewTime 与 fuel；
- ledger token；
- 已完成输出。

### 9.2 炼金锅 journal

- FluidTank 的流体 ID、NBT 与数量；
- 每个输入槽、cooktime；
- 已投入试剂；
- 各 recipe edge 的完成位置；
- 已产生副产物；
- ledger token。

共同原则：实际机器状态优先于计划快照；已发生世界副作用时禁止同时退款和交付预测结果。

## 10. JEI 与计划界面

### 10.1 原版炼药台

复用 JEI brewing category 的输入/试剂/输出语义，但 RSI 按钮发送精确输入和输出 NBT，不只发送 Potion ID。

### 10.2 Iron 炼金锅

原生 `AlchemistCauldronJeiRecipe` 已能表达物品输入、流体输入、多个流体结果和副产物，可作为展示数据参考；执行时仍回查服务端 recipe definition。

动态卷轴和原版药水流体配方需要稳定 synthetic ID，例如：

```text
rs_integration:iron_cauldron/potion/<input>/<reagent>/<output>
rs_integration:iron_cauldron/scroll/<spell>/<level>
```

计划界面必须显示 mB 数量、副产物、容器余留和所选机器类型。

## 11. 测试矩阵

### 11.1 原版炼药台

- 水瓶 -> 粗制药水；
- 基础 -> 延长/增强；
- 普通 -> 喷溅 -> 滞留；
- 一次 1/2/3 瓶；
- 模组注册 brewing mix；
- 燃料不足时提交前拒绝；
- 中途破坏或换槽不复制、不吞物品。

### 11.2 Iron 炼金锅

- common ink -> uncommon -> rare -> epic -> legendary 递归链；
- 普通药剂 -> greater 药剂；
- Fill 与 Empty 容器余留；
- 只有副产物、无流体输出的 soak；
- 多流体结果；
- FluidStack NBT 不同不得混合；
- 结果放不下时提交前拒绝；
- 同锅连续链中断后的实际状态恢复；
- 动态原版药水和卷轴配方均可发现。

### 11.3 跨系统

- 同一药剂同时存在炼药台和炼金锅路线时能选择候选；
- 缺少某类绑定机器时自动选择另一条可行路线；
- 容器循环不产生免费物品或流体；
- 所有实际输出 ItemStack/FluidStack 与计划精确一致。

## 12. 实施顺序

1. 建立统一 Item/Fluid material identity 与 conversion graph；
2. 实现原版 `PotionBrewing` 服务端目录和纯规划测试；
3. 实现原版炼药台 delegate；
4. 实现 Iron datapack recipe catalog；
5. 补充 Iron 动态药水与卷轴 recipe definitions；
6. 实现炼金锅单步 delegate；
7. 实现同锅连续流体递归；
8. 接入 JEI、计划展示、候选评分；
9. 完成事务恢复、批量和并发测试；
10. 最后与 Goety 女巫坩埚目标 NBT planner 共享统一候选入口。

## 13. 验收标准

- 原版药水能够从水瓶递归到目标等级与形态；
- Iron 墨水和高级药剂能够从基础流体递归炼制并正确装瓶；
- 动态卷轴和原版药水锅内配方不会遗漏；
- 流体数量、NBT、容器余留和副产物守恒；
- 缺少燃料、空间、流体或绑定机器时不提前提取 RS 材料；
- 取消、掉线、机器破坏和外部干预均不吞物品、不复制；
- 三套系统可以共享规划入口，但永远由各自专用 delegate 执行。
