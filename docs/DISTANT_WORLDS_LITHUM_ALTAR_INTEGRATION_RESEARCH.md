# Distant Worlds Lithum Altar 与 RS Integration 集成研究

## 1. 文档目的

本文档记录对以下模组版本的逆向研究结果，并给出将其 Lithum Altar 接入 `rs-integration` 的完整技术方案。

```text
目标模组：Distant Worlds Reborn
文件：DistantWorlds-Reborn-1.20.1-v1.1.0-Beta.jar
Minecraft：1.20.1
Forge：47.x
模组 ID：distant_worlds
```

目标功能：

1. 在 JEI 中正确展示 Lithum Altar 的研究/加工配方；
2. 使用 RS 终端绑定 Lithum Altar Core；
3. 从 RS 网络提取材料并放入祭坛 pedestal/core；
4. 自动设置当前祭坛配方并启动研究；
5. 祭坛能量不足时，在附近 Lithum Furnace 自动补充燃料；
6. 等待祭坛真实完成并收取结果；
7. 在区块卸载、机器失效、材料不足、燃料不足、玩家断线等路径下保证物品守恒。

本文只描述研究结果和实施设计，不等同于已经完成代码实现。

---

## 2. 最终结论

Distant Worlds 的 Lithum Altar **不是普通的 Forge `Recipe` 机器**。

它没有一个可以直接交给 `RecipeManager`、通过 `assemble()` 执行的标准祭坛配方类型。祭坛配方主要由 MCreator 生成的过程类硬编码判断，启动后通过 Lithum Core 的 BlockEntity 持久化数据驱动 tick 逻辑。

因此不能只做以下工作：

```java
registration.addRecipes(...);
```

也不能只复用通用 `GenericBatchDelegate`，因为 Generic Delegate 无法自动完成：

- 多方块结构验证；
- pedestal 材料布局；
- `CurrentRecipe` 状态写入；
- Lithum Core 能量状态读取；
- Lithum Furnace 燃料补给；
- Distant Worlds 的真实结果生成；
- 研究系统和玩家解锁状态校验。

正确方案是新增一个 Distant Worlds 专用集成模块：

```text
DistantWorldsRSModule
├── LithumAltarRecipeHandler
├── LithumAltarRecipeWrapper
├── LithumAltarBatchDelegate
├── LithumAltarFuelHelper
├── LithumAltarStructureHelper
└── DistantWorldsReflection
```

其中：

```text
JEI 展示层       -> LithumAltarRecipeWrapper
配方解析层       -> LithumAltarRecipeHandler / Resolver
绑定层           -> LithumCore 绑定目标
执行层           -> LithumAltarBatchDelegate
燃料补给层       -> LithumAltarFuelHelper
状态/兼容层      -> DistantWorldsReflection
```

---

## 3. JAR 中确认的核心类

### 3.1 Lithum Core

```text
net.mcreator.distantworlds.block.LithumCoreBlock
net.mcreator.distantworlds.block.entity.LithumCoreBlockEntity
net.mcreator.distantworlds.world.inventory.LithumCoreGUIMenu
```

`LithumCoreBlock` 的行为包括：

- 右键触发 Lithum Core 交互过程；
- 定时 tick 触发祭坛运行过程；
- 打开 `LithumCoreGUIMenu`；
- 生成 `LithumCoreBlockEntity`；
- 通过 BlockEntity 的 ItemHandler 暴露内部槽位。

`LithumCoreBlockEntity`：

```text
父类：RandomizableContainerBlockEntity
接口：WorldlyContainer
槽位数：2
```

根据构造和 GUI 反编译结果：

```text
slot 0：核心输入/结果槽
slot 1：核心模块槽
```

Lithum Core 的内部槽位通过 Forge `ITEM_HANDLER` capability 暴露。核心 GUI 的两个槽位分别绑定到内部 ItemHandler 的 0 和 1。

### 3.2 Lithum pedestal

```text
net.mcreator.distantworlds.block.LithumPedestalBlock
net.mcreator.distantworlds.block.entity.LithumPedestalBlockEntity
net.mcreator.distantworlds.world.inventory.LithumPedestalGUIMenu
```

Pedestal 同样是一个容器型 BlockEntity，并通过 ItemHandler 暴露物品。祭坛配方的外围材料由 pedestal 提供，而不是全部放在核心 slot 0。

### 3.3 能源和辅助机器

```text
net.mcreator.distantworlds.block.LithumFurnaceBlock
net.mcreator.distantworlds.block.entity.LithumFurnaceBlockEntity
net.mcreator.distantworlds.block.LithumConverterBlock
net.mcreator.distantworlds.block.entity.LithumConverterBlockEntity
net.mcreator.distantworlds.block.LithumStorage*Block
net.mcreator.distantworlds.block.LithumTransmitterBlock
```

需要区分：

- Lithum Core 祭坛能源网络；
- Lithum Furnace 的燃料槽；
- Lithum Converter 自己的 Forge Energy capability。

祭坛核心的 `CurrentEnergy` 是 Distant Worlds 自己的 NBT 数值，不等同于通用 Forge Energy。

### 3.4 研究相关类

```text
net.mcreator.distantworlds.block.GarhennaResearchesTableBlock
net.mcreator.distantworlds.block.entity.GarhennaResearchesTableBlockEntity
net.mcreator.distantworlds.world.inventory.GarhennaResearchesTableGUIMenu
```

研究台本身是独立的三槽容器，主要用于 Garhenna 研究系统，不应与 Lithum Altar Core 混为一谈。

---

## 4. 祭坛的真实状态模型

Lithum Core 的运行状态保存在核心 BlockEntity 的 persistent data 中。

已确认的关键键：

```text
CurrentRecipe
CurrentEnergy
MaxEnergy
```

其他过程类还会使用：

```text
Recovery
MaxRecovery
```

这些键是 Distant Worlds 自身过程逻辑的状态协议。RS Integration 应优先通过这些状态判断祭坛生命周期，而不是根据固定时间猜测完成。

### 4.1 空闲状态

```text
CurrentRecipe = ""
```

空闲时，核心可以接受新的祭坛启动请求。

### 4.2 运行状态

```text
CurrentRecipe != ""
CurrentEnergy < MaxEnergy
```

祭坛会持续 tick，并根据结构中的能源部件恢复能量。

### 4.3 完成状态

运行过程大致满足：

```text
CurrentRecipe != ""
CurrentEnergy == MaxEnergy
CurrentEnergy != 0
```

满足条件后：

1. 调用 `LithumCoreUpdateResultProcedure` 生成结果；
2. 清空 `CurrentRecipe`；
3. 清零 `CurrentEnergy`；
4. 触发方块更新和完成音效；
5. 结果可能进入核心槽位或由过程逻辑生成/放置。

### 4.4 失败或中断状态

如果祭坛结构失效、能源系统不可用、区块被卸载或核心被破坏，不能将其直接视为成功。

RS Delegate 必须区分：

```text
WAITING_FOR_START
WORKING
DONE
FAILED
```

只有观察到有效启动并最终确认真实结果时，才允许提交当前步骤成功。

---

## 5. 多方块结构

祭坛结构检查类：

```text
net.mcreator.distantworlds.procedures.LithumStructureIntegrityCheckProcedure
```

已确认它使用以下 block tag：

```text
#distant_worlds:lithum_altar_base_layer_blocks
#distant_worlds:lithum_altar_upper_layer_main_blocks
#distant_worlds:lithum_altar_upper_layer_frame_blocks
#distant_worlds:lithum_altar_upper_layer_additional_blocks
```

结构检查不是简单判断“绑定方块是不是 Lithum Core”。启动前必须检查结构完整性。

### 5.1 绑定位置

绑定必须以 Lithum Core 的坐标为根坐标：

```text
绑定点 = LithumCoreBlock 的 BlockPos
```

不建议允许玩家点击 pedestal 后把 pedestal 位置当作祭坛机器位置，因为：

- `CurrentRecipe` 在核心 BE 上；
- `CurrentEnergy` 在核心 BE 上；
- 结果和启动逻辑围绕核心坐标执行；
- pedestal 只是 support resource。

### 5.2 Support scope

祭坛执行不只占用核心一个方块，还会占用：

- 所有参与输入的 pedestal；
- 结构检查覆盖的方块；
- 能量生成、存储和传输辅助机器；
- 可能被自动补燃料的 Lithum Furnace。

因此在 DAG 并发能力声明中，必须把这些邻接位置纳入 machine/support scope。不能只锁主核心，否则两个邻近祭坛可能同时争用同一组 pedestal、能源或炉子。

第一版如果无法精确解析全部 support positions，应保持 exclusive，不要宣称支持并发。

---

## 6. 祭坛配方发现机制

祭坛配方选择类：

```text
net.mcreator.distantworlds.procedures.LithumStructureRecipePickerProcedure
```

它不是从 `RecipeManager` 查询 `RecipeType`，而是：

1. 读取核心和 pedestal 的物品；
2. 读取模块、附魔和玩家状态；
3. 通过一系列物品判断选择字符串；
4. 将字符串写入核心 BE 的 `CurrentRecipe`。

### 6.1 已确认的配方类别

从字节码常量和判断分支中确认的类别包括：

```text
firon_*
helyst_*
vairis_*
ubricite_*
module_*
enchantment_*
distant_worlds:encased_soul
distant_worlds:living_metal
distant_worlds:strong_shell
```

其中 `module_*` 可能包含：

```text
module_calm_flame
module_raging_flame
module_calm_spark
module_raging_spark
module_increased_storage
module_charmed_pedestal
module_flow_filter
module_flow_amplification
module_core_stabilization
module_core_destabilization
```

具体的完整输入组合应以目标 JAR 的过程逻辑为准，并在实现中通过专用 resolver 保持可诊断，而不是依赖扫描任意字段猜测。

### 6.2 输出不应从 `getResultItem()` 猜测

Distant Worlds 没有一个统一的祭坛 `Recipe` 对象。因此以下做法不可靠：

```java
recipe.getResultItem(...)
```

应建立显式的 `LithumAltarRecipeDefinition`，至少包含：

```java
ResourceLocation id;
List<IngredientSpec> coreInputs;
List<IngredientSpec> pedestalInputs;
ItemStack result;
long baseEnergy;
long baseRecovery;
ResearchRequirement researchRequirement;
boolean deterministic;
```

其中 `id` 应与 Distant Worlds 的 `CurrentRecipe` 字符串建立映射。

### 6.3 研究配方和普通材料配方

推荐在 wrapper 中区分：

```java
enum LithumRecipeKind {
    MATERIAL,
    MODULE,
    ENCHANTMENT,
    RESEARCH,
    UNKNOWN
}
```

未知类别不应自动放入 RS 合成候选。JEI 可以展示，但执行层必须 fail closed。

---

## 7. 能量需求和恢复速度

### 7.1 最大能量

`LithumCoreUpdateMaxEnergyProcedure` 的基础值如下：

| 配方字符串包含 | 基础最大能量 |
|---|---:|
| `firon` | 200000 |
| `helyst` | 120000 |
| `vairis` | 80000 |
| `ubricite` | 40000 |
| `module` | 10000 |
| `enchantment` | 10000 |

计算还会乘以：

```text
core module multiplier
× ConfigCommonConfiguration.REQUIRED_ENERGY_MODIFIER
```

核心模块：

```text
module_core_stabilization -> x 1.25
module_core_destabilization -> x 0.75
其他 -> x 1.0
```

### 7.2 能量恢复

`LithumCoreUpdateRecoveryProcedure` 的基础恢复值如下：

| 配方字符串包含 | 基础恢复值 |
|---|---:|
| `firon` | 2000 |
| `helyst` | 1200 |
| `vairis` | 1000 |
| `ubricite` | 800 |
| `module` | 500 |
| `enchantment` | 500 |

之后还会受到外围 flow 模块影响：

```text
module_flow_filter        -> 恢复值 / 1.2
module_flow_amplification -> 恢复值 x 1.2
```

最终还会乘以：

```text
ConfigCommonConfiguration.RECOVERY_MODIFIER
```

### 7.3 JEI 展示建议

JEI wrapper 应至少展示：

```text
能量需求：N
基础恢复：N/tick 或模组实际显示单位
需要 Lithum Core
需要 Lithum Altar 多方块结构
```

如果无法在静态 JEI 阶段读取服务器配置，应展示基础值并在 Tooltip 中注明配置修正由服务器决定。

计划界面如果已经有绑定机器状态，则可以进一步显示：

```text
当前能量 / 最大能量
预计恢复速度
预计等待时间
缺少的能源结构
```

估算时间只能作为提示，不能作为完成判断依据。

---

## 8. 燃料系统研究结果

已确认燃料标签：

```text
#distant_worlds:lithum_furnace_fuel
```

当前 JAR 中的标签内容：

```text
distant_worlds:raw_curelite
distant_worlds:curelite
distant_worlds:raw_curelite_block
distant_worlds:curelite_block
```

### 8.1 正确的自动补燃料流程

当绑定祭坛能量不足时：

```text
1. 读取核心 CurrentEnergy / MaxEnergy
2. 检查祭坛 CurrentRecipe 是否仍在运行
3. 搜索绑定核心附近的 Lithum Furnace
4. 读取炉子燃料槽的真实 ItemHandler
5. 判断现有燃料是否符合 distant_worlds:lithum_furnace_fuel
6. 计算是否需要补充燃料
7. 对 RS 网络执行 SIMULATE 提取
8. 确认目标炉子可接受这些燃料
9. 提交 RS 提取
10. 将燃料放入真实炉子燃料槽
11. 调用 setChanged()
12. 继续等待祭坛状态变化
```

### 8.2 不能直接写入 CurrentEnergy

不建议通过以下方式“快速完成”：

```java
core.getPersistentData().putDouble("CurrentEnergy", maxEnergy);
```

原因：

- 绕过 Distant Worlds 的能源结构；
- 可能绕过炉子燃料消耗；
- 可能破坏恢复速度和模块效果；
- 可能导致结果生成与玩家正常玩法不一致；
- 可能造成服务器重载时状态不一致。

RS Integration 的正确职责是：

```text
提供燃料 -> 让 Distant Worlds 自己恢复能量
```

而不是：

```text
直接伪造祭坛能量
```

### 8.3 补燃料的事务顺序

燃料补给涉及另一次物品移动，必须遵守物品守恒：

```text
检查目标炉子
-> SIMULATE 目标槽位插入
-> SIMULATE RS 提取
-> 提交提取
-> EXECUTE 插入
-> 验证插入结果
```

如果提交后插入失败：

1. 立即尝试将燃料插回 RS；
2. 插不回时返还玩家；
3. 背包也无法接收时生成明确的掉落实体；
4. 记录 error 日志；
5. 不继续启动祭坛。

不能先提交祭坛材料，再发现燃料炉不存在，最后让材料留在 pedestal 中无人管理。

### 8.4 附近炉子选择

建议按以下优先级选择：

```text
1. 同一祭坛 support scope 内的炉子
2. 距核心最近且当前燃料槽兼容的炉子
3. 当前已经连接能源结构的炉子
4. 其他绑定范围内的炉子
```

如果存在多个可用炉子，执行器必须锁定具体炉子位置，不能每 tick 随意切换目标。

---

## 9. JEI 集成方案

### 9.1 JEI 的职责

JEI 负责：

- 展示祭坛配方；
- 显示输入材料和输出；
- 显示 Lithum Core 图标；
- 显示能源和研究要求；
- 将配方传递到 RSI 侧边栏或计划系统；
- 提供绑定/执行入口。

JEI 不应直接执行世界操作。

### 9.2 建议的 JEI UID

建议新增：

```text
distant_worlds:lithum_altar
```

并在 `ModType.configureJei()` 中映射：

```java
ModType.configureJei(
    "distant_worlds_lithum_altar",
    new String[][]{{"distant_worlds:lithum_altar", "distant_worlds_lithum_altar"}},
    new String[][]{{"com.huanghuang.rsintegration.mods.distantworlds.", "distant_worlds_lithum_altar"}},
    "gui.rs_integration.jei.distant_worlds_lithum_altar"
);
```

实际实现应根据项目现有 `ModType` 注册约定调整，但 UID、filter、ModType ID 必须严格一致。

### 9.3 JEI wrapper

建议新增：

```text
src/main/java/com/huanghuang/rsintegration/mods/distantworlds/LithumAltarRecipeWrapper.java
```

wrapper 不应伪装成 Distant Worlds 原生 `Recipe`，而是 RSI 内部的显示模型。建议字段：

```java
ResourceLocation recipeId;
LithumRecipeKind kind;
List<Ingredient> coreIngredients;
List<Ingredient> pedestalIngredients;
ItemStack output;
int energy;
int recovery;
boolean requiresResearch;
String currentRecipeValue;
```

### 9.4 JEI 输入布局

推荐按实际机器位置表达：

```text
[核心输入]

[pedestal 1] [pedestal 2] [pedestal 3]
[pedestal 4] [Lithum Core] [pedestal 5]
[pedestal 6] [pedestal 7] [pedestal 8]

[燃料] [能量] -> [结果]
```

如果 JEI 类别空间不足，可以使用分组：

```text
核心材料
祭坛 pedestal 材料
能源燃料
结果
```

不要把所有输入无序地压缩到普通 3×3 工作台布局，否则玩家无法理解实际摆放方式。

### 9.5 配方传输

当前项目已有 JEI 和 RSI 侧边栏桥接逻辑。Distant Worlds 应使用专用传输数据，而不是假设是普通 `RecipeTransferHandler`。

传输至少需要携带：

```text
recipeId
目标绑定类型
核心输入候选
pedestal 输入候选
当前研究状态
```

客户端不能提交可信输出、能量数值或最终 `CurrentRecipe`。服务端必须重新通过 resolver 验证。

---

## 10. 绑定方案

### 10.1 绑定目标

新增模块时注册目标：

```java
BindingEventHandler.registerTarget(
    new BindingEventHandler.MachineBindingTarget(
        "distant_worlds",
        ModType.byId("distant_worlds_lithum_altar"),
        RSIntegrationConfig.ENABLE_DISTANT_WORLDS,
        List.of("net.mcreator.distantworlds.block.LithumCoreBlock"),
        "distant_worlds_lithum_altar",
        true
    )
);
```

Lithum Core 有 GUI，因此 `supportsGui` 可以为 `true`。但是 RS 自动执行仍然应绑定核心 BlockPos，而不是只打开其 GUI。

### 10.2 绑定时检查

绑定阶段不一定要求完整结构已经完成，但执行阶段必须检查：

```text
BlockPos 仍是 LithumCoreBlock
BlockEntity 仍是 LithumCoreBlockEntity
区块可加载
多方块结构完整
```

如果绑定时就强制验证结构，玩家建造过程中会不方便；建议：

```text
绑定：允许记录核心位置
启动：严格验证完整结构
```

### 10.3 解绑和失效

以下情况必须使绑定执行失效：

- 核心被破坏；
- 核心方块被替换；
- 维度不存在；
- 区块无法加载；
- 绑定物品 NBT 损坏；
- 结构已经不完整。

失效时不能继续从 RS 提取材料。

---

## 11. 专用 Delegate 设计

建议新增：

```text
src/main/java/com/huanghuang/rsintegration/mods/distantworlds/LithumAltarBatchDelegate.java
```

继承：

```java
AbstractBatchDelegate
```

### 11.1 `validateAndInit`

必须完成：

```text
1. 解析维度和绑定位置
2. 加载/确认目标区块
3. 确认 BlockState 是 LithumCoreBlock
4. 确认 BE 是 LithumCoreBlockEntity
5. 确认 CurrentRecipe 为空
6. 检查核心槽位状态
7. 检查多方块结构
8. 搜索并记录可用 pedestal
9. 解析目标 Distant Worlds 配方
10. 检查研究解锁状态
11. 检查材料数量和摆放容量
12. 确认燃料/能源补给可行
```

不要在 `validateAndInit` 中提前从 RS 物理提取材料。

### 11.2 `getRequiredMaterials`

返回顺序必须与真实放置顺序一致，例如：

```text
[0] 核心 slot 0 材料
[1] pedestal 0 材料
[2] pedestal 1 材料
...
```

如果某些输入只是匹配条件而不消耗，必须在 resolver 中明确标识，不能把所有识别用物品都当成消耗材料。

### 11.3 `tryStartSingleCraft`

推荐严格分阶段：

```text
Phase 1：重新验证机器和结构
Phase 2：创建 ExtractionLedger
Phase 3：预留核心材料和 pedestal 材料
Phase 4：预留燃料
Phase 5：commit 材料账本
Phase 6：将材料放入核心和 pedestal
Phase 7：确认 Distant Worlds 识别到目标输入
Phase 8：必要时提交并放入燃料
Phase 9：写入/触发 CurrentRecipe
Phase 10：确认进入 WORKING
```

如果材料已经提交但物理放置失败：

```text
清空已写入核心/ pedestal 的物品
refundCommitted()
```

不能只调用 `ledger.close()` 期待已提交物品自动退款。项目约定中，`close()` 对 `COMMITTED` 是 no-op，必须显式 `refundCommitted()` 或对应 rollback 流程。

### 11.4 如何启动祭坛

Distant Worlds 的原生右键逻辑是由 `LithumCoreOnBlockRightClickedProcedure` 触发的，条件包括手持：

```text
distant_worlds:dalite_staff
```

RS Integration 有两种方案：

#### 方案 A：复用原生启动过程

通过反射调用 Distant Worlds 的过程类：

```text
LithumCoreOnBlockRightClickedProcedure.execute(...)
```

优点：

- 最大限度复用原模组逻辑；
- 研究条件、经验、结构检查和副作用由原模组处理；
- 兼容目标 JAR 当前行为。

缺点：

- 过程类是 MCreator 生成内部实现，不是稳定 API；
- 需要准备一个真实 `dalite_staff` ItemStack；
- 需要处理原生过程对玩家手持和背包的修改；
- 反射签名对版本变化敏感。

#### 方案 B：复刻最小启动协议

RS Integration 自己：

1. 验证结构；
2. 验证材料；
3. 写入 `CurrentRecipe`；
4. 设置能量初始状态；
5. 让原始 tick 逻辑接管。

不推荐作为第一版，因为：

- `LithumStructureRecipePickerProcedure` 还有复杂的输入和玩家状态判断；
- 原生过程可能消耗 staff、经验或触发成就；
- 复刻过程逻辑容易与目标模组不一致。

**推荐：第一版优先使用方案 A，并将 Distant Worlds 类名集中放入 Reflection 探针。**

如果原生过程无法可靠调用，再实现方案 B 的显式兼容分支，并加版本诊断。

### 11.5 `isMachineCraftFinished`

完成判断建议：

```text
如果尚未成功进入 WORKING：返回 false
如果核心不存在/被替换：返回失败，不是成功
如果 CurrentRecipe 仍非空：返回 false
如果 CurrentRecipe 已清空：检查真实结果
如果核心输出槽存在真实结果：返回 true
如果世界掉落结果存在：通过 capture contract 捕获
如果没有真实结果：返回 false 或失败，禁止模板补发
```

重要：不能只看 `CurrentRecipe == ""`，因为玩家手动清空、机器破坏或启动失败也可能导致空值。

Delegate 应记录：

```java
boolean ritualEverStarted;
boolean resultObserved;
```

只有 `ritualEverStarted == true` 且观察到真实结果/合法完成状态时才允许成功。

### 11.6 `collectResult`

如果结果在核心槽位：

- 只收真实存在的 ItemStack；
- 外部管道已经取走时返回空；
- 禁止用 wrapper 中的模板结果补发。

如果结果是世界掉落：

- 使用 `getExpectedOutput()`；
- 使用精确 `getOutputCaptureRegion()`；
- 通过 `CraftOutputInterceptor` 捕获；
- 多产物必须覆写 `collectAllResults()`。

### 11.7 清理和退款

`clearMachineState` 至少清理：

```text
核心 slot 0
核心临时模块/输入状态（仅清理本次写入的内容）
pedestal 中本次放入的材料
燃料炉中本次插入但未被消耗的燃料
CurrentRecipe（仅在确认属于本次操作时）
CurrentEnergy（谨慎处理，不能覆盖玩家原本能量）
```

不能无条件清空整个核心或整个炉子库存，因为这些物品可能来自玩家手动操作或其他自动化。

---

## 12. 研究解锁处理

Distant Worlds 存在 Garhenna 研究和物品解锁逻辑，相关类包括：

```text
net.mcreator.distantworlds.procedures.ItemUnlockRecipesProcedure
```

同时 JAR 中存在：

```text
#distant_worlds:researched_garhenna_items
```

### 12.1 推荐策略

第一版不要让 RS 自动化绕过研究系统。

建议执行前检查：

```text
玩家是否满足 Distant Worlds 当前研究条件
目标物品是否已经解锁
目标配方是否属于允许的研究阶段
```

如果无法可靠读取研究状态：

```text
JEI 可以展示
RS 自动执行拒绝
计划界面显示“需要完成 Distant Worlds 研究”
```

### 12.2 为什么不能忽略研究

如果 JEI/RS 可以直接执行尚未研究的内容，会产生：

- 绕过模组进度；
- 研究书和实际解锁状态不一致；
- 玩家无法按原模组设计推进；
- 后续 Distant Worlds 更新后出现逻辑冲突。

---

## 13. Reflection 设计

项目现有约定是：

- 类名集中在 `reflection/probes/`；
- 探针只存 `Class<?>`；
- 字段和方法在调用点通过 `Reflect` 查找；
- 不缓存跨 tick 的 BlockEntity 或 ItemHandler；
- 目标模组不存在时主模组仍能加载。

建议新增：

```text
src/main/java/com/huanghuang/rsintegration/reflection/probes/DistantWorldsReflection.java
```

建议探针字段：

```java
public static Class<?> lithumCoreBlockClass;
public static Class<?> lithumCoreBlockEntityClass;
public static Class<?> lithumPedestalBlockClass;
public static Class<?> lithumPedestalBlockEntityClass;
public static Class<?> lithumFurnaceBlockClass;
public static Class<?> lithumFurnaceBlockEntityClass;
public static Class<?> lithumCoreClickProcedureClass;
public static Class<?> lithumRecipePickerProcedureClass;
public static Class<?> lithumStructureIntegrityClass;
public static Class<?> lithumCoreUpdateTickClass;
```

不要在探针中保存 `Field` 或 `Method`。

不要反射原版方法。例如 `setChanged()` 应通过：

```java
if (be instanceof BlockEntity blockEntity) {
    blockEntity.setChanged();
}
```

只对 Distant Worlds 自己的过程类和映射不稳定的模组类使用反射。

---

## 14. Module 和配置注册

### 14.1 ModIds

新增：

```java
public static final String DISTANT_WORLDS = "distant_worlds";
```

### 14.2 配置

新增配置：

```java
ENABLE_DISTANT_WORLDS
```

建议默认值：

```text
true 或与其他可选模组集成一致
```

建议额外配置：

```text
Distant Worlds 自动补充燃料开关
Distant Worlds 是否允许自动使用 dalite_staff
Distant Worlds 是否尊重研究解锁
Distant Worlds 燃料补给搜索半径
Distant Worlds 最大等待时间
```

推荐至少保留：

```text
enableDistantWorlds
allowDistantWorldsFuelAutomation
```

### 14.3 Module

建议新增：

```text
src/main/java/com/huanghuang/rsintegration/mods/distantworlds/DistantWorldsRSModule.java
```

职责：

```text
configFlag()
modId()
registerModType()
registerBindingTargets()
registerRecipeHandler()
registerNetworkPackets()
initCommon()
```

在 `RSIntegrationMod.MODULES` 中通过 `ModList`/配置开关注册。

目标模组不存在时，不得触发硬链接类加载失败。

---

## 15. RecipeHandler 设计

Distant Worlds 的祭坛配方不是原生 Recipe，因此普通 `ModRecipeHandler` 可能不够。

建议新增两层：

```text
LithumAltarRecipeResolver
  -> 读取/构造 LithumAltarRecipeDefinition

LithumAltarRecipeHandler
  -> 将安全定义转换为 RSI Recipe/计划索引条目
```

### 15.1 Resolver 职责

- 解析目标 JAR 当前版本的配方类别；
- 建立 `CurrentRecipe` 映射；
- 识别核心材料、pedestal 材料、模块和附魔；
- 识别输出；
- 计算基础能量和恢复值；
- 判断研究要求；
- 判断是否确定性；
- 对未知配方返回 unsupported。

### 15.2 Handler 过滤规则

可以加入 RSI 自动合成候选的配方必须满足：

```text
输入可以准确表示为 Ingredient
输出是确定性的 ItemStack
没有未识别的世界副作用
结构要求可以由 Delegate 验证
研究状态可以由 Delegate 验证
执行路径可以回滚或 fail closed
```

以下配方默认只展示，不自动化：

```text
未知 CurrentRecipe
输出依赖随机数且无法确定产量
需要无法读取的玩家/实体上下文
会改变全局世界状态
会生成无法捕获的实体或方块
输出不是 ItemStack 且没有明确收集协议
```

---

## 16. 并发能力和资源所有权

第一版建议：

```text
Distant Worlds Lithum Altar = exclusive
```

原因：

- 一个祭坛使用多个 pedestal；
- 能源结构可能共享；
- 附近炉子可能被多个执行使用；
- Distant Worlds 过程逻辑通过世界坐标扫描；
- 当前无法保证所有 support offset 都完整可枚举。

如果后续要开放并发，必须完整声明：

```text
materials = CHAIN_RESERVED
outputOwnership = 明确的核心槽或 OWNED_WORLD_CAPTURE
cleanup = 可离线清理
sideEffects = machine-local
supportOffsets = 精确结构坐标
```

资源获取顺序必须保持项目约定：

```text
operation budget
-> 完整 machine/support scope
-> capture lease
-> commit
-> start
```

不能先 claim operation，再发现 pedestal/炉子冲突。

---

## 17. 失败路径与物品守恒

### 17.1 物品守恒公式

对每次祭坛执行，应满足：

```text
初始 RS 物品
+ 初始玩家物品
+ 初始机器物品
= 最终 RS 物品
+ 最终玩家物品
+ 最终机器物品
+ 合法消耗
+ 合法输出
+ 明确掉落实体
```

### 17.2 必须覆盖的失败路径

```text
1. 配方不存在
2. 核心不存在
3. 核心被替换
4. 结构不完整
5. CurrentRecipe 已运行
6. pedestal 不足
7. pedestal 有残留物
8. RS 材料不足
9. staff 不足或不可用
10. 研究未解锁
11. 附近没有能源结构
12. 附近没有可用炉子
13. RS 有燃料但目标槽不接受
14. 材料放置中途失败
15. 启动过程拒绝
16. 启动后核心被破坏
17. 区块卸载
18. 玩家断线
19. 服务器停止
20. 结果被外部管道提前取走
21. 世界掉落被玩家/磁铁提前取走
22. 计划 abort
23. 链式上游步骤成功、祭坛步骤失败
```

### 17.3 失败时的关键规则

- `COMMITTED` 物品必须显式退款；
- 不要依赖 `ExtractionLedger.close()` 退款；
- 共享 ledger 下由链级调度负责退款，Delegate 不得重复退款；
- 结果槽为空时不得用配方模板补发；
- 世界输出缺失时 fail closed；
- 清理只清理本次操作实际写入的物品；
- 不能删除玩家原本的 `CurrentEnergy` 或已有机器物品。

---

## 18. 日志和诊断

建议统一使用日志前缀：

```text
[RSI-DW]
[RSI-DW-Recipe]
[RSI-DW-Batch]
[RSI-DW-Fuel]
[RSI-DW-Structure]
```

关键日志字段：

```text
player
recipeId
currentRecipe
core dimension/position
energy current/max
fuel item/count
fuel furnace position
pedestal positions
research state
delegate state
```

Tick 路径必须使用 `warnOnce()` 或受诊断开关控制的 debug，不能每 tick 输出堆栈。

建议在 debug 日志中记录状态转换：

```text
WAITING_FOR_START -> WORKING
WORKING -> REFUELING
REFUELING -> WORKING
WORKING -> DONE
WORKING -> FAILED
```

---

## 19. 分阶段实施计划

### 阶段 0：兼容探针和结构研究

- [ ] 新增 `DistantWorldsReflection`；
- [ ] 注册 `distant_worlds` ModId；
- [ ] 读取并验证 Lithum Core、pedestal、furnace 类；
- [ ] 增加启动期 ContractValidation；
- [ ] 在目标模组不存在时验证主模组正常启动。

### 阶段 1：绑定和只读状态

- [ ] 注册 Lithum Core 绑定目标；
- [ ] 绑定到核心位置；
- [ ] 实现结构完整性读取；
- [ ] 实现 `CurrentRecipe`、`CurrentEnergy`、`MaxEnergy` 读取；
- [ ] 计划界面显示结构、能量和研究警告；
- [ ] 暂不执行材料提取。

### 阶段 2：配方定义和 JEI 展示

- [ ] 新增 `LithumAltarRecipeDefinition`；
- [ ] 新增 `LithumAltarRecipeResolver`；
- [ ] 新增 JEI wrapper/category；
- [ ] 展示核心输入、pedestal 输入、结果；
- [ ] 展示能量和恢复信息；
- [ ] 未知配方只展示不自动化；
- [ ] 增加中英文语言键。

### 阶段 3：材料放置和原生启动

- [ ] 新增 `LithumAltarBatchDelegate`；
- [ ] 实现 RS 材料预留；
- [ ] 实现核心/pedestal 物品放置；
- [ ] 使用 `dalite_staff` 启动原生过程；
- [ ] 观察 `CurrentRecipe` 进入运行状态；
- [ ] 实现失败退款和清理。

### 阶段 4：自动补充燃料

- [ ] 新增共享 `LithumAltarFuelHelper`；
- [ ] 搜索附近 Lithum Furnace；
- [ ] 读取燃料标签；
- [ ] SIMULATE 提取和插入；
- [ ] 正确提交燃料；
- [ ] 插入失败时退款；
- [ ] 等待祭坛自身恢复能量；
- [ ] 禁止直接修改 CurrentEnergy。

### 阶段 5：真实结果收集

- [ ] 确认结果槽真实位置；
- [ ] 必要时实现世界掉落 capture；
- [ ] 支持多个输出；
- [ ] 外部管道提前取走时 fail closed；
- [ ] 完成时只收真实结果；
- [ ] 结束后清理本次输入。

### 阶段 6：计划和链式合成

- [ ] 接入 `IngredientSpec`；
- [ ] 处理核心材料和 pedestal 材料顺序；
- [ ] 处理研究条件；
- [ ] 处理能量不足提示；
- [ ] 处理燃料补给的 supplemental reservation；
- [ ] 与 shared ledger 正确协作；
- [ ] 第一版保持 exclusive。

### 阶段 7：兼容性和优化

- [ ] 验证 Distant Worlds 版本变化；
- [ ] 验证模块和附魔配方；
- [ ] 验证多个祭坛相邻场景；
- [ ] 验证服务器重启和区块卸载；
- [ ] 评估是否开放安全并发；
- [ ] 增加诊断命令和覆盖率报告。

---

## 20. 测试清单

### 20.1 JAR/兼容性

- [ ] Distant Worlds 未安装时 `compileJava` 成功；
- [ ] Distant Worlds 已安装时探针全部可用；
- [ ] 目标类缺失时集成自动降级；
- [ ] Distant Worlds 版本不匹配时有明确日志。

### 20.2 绑定

- [ ] 点击 Lithum Core 可以绑定；
- [ ] 点击 pedestal 不会产生错误根绑定；
- [ ] 解绑后绑定数据清除；
- [ ] 核心被替换后执行拒绝；
- [ ] 跨维度绑定按项目现有规则工作。

### 20.3 结构

- [ ] 完整结构允许执行；
- [ ] 缺少底层方块时拒绝；
- [ ] 缺少上层方块时拒绝；
- [ ] 框架方块错误时拒绝；
- [ ] 共享结构和邻近祭坛不会串用 pedestal。

### 20.4 材料

- [ ] 材料全部存在时正确放置；
- [ ] 材料不足时不部分提取；
- [ ] 核心材料和 pedestal 材料顺序正确；
- [ ] NBT 敏感输入保持完整；
- [ ] 放置中途失败时全部退款；
- [ ] 外部管道已有材料时不覆盖或吞物。

### 20.5 启动

- [ ] 有 `dalite_staff` 时启动成功；
- [ ] staff 从 RS 提取后使用次数正确；
- [ ] staff 从玩家背包使用时来源正确；
- [ ] staff 不可用时材料退款；
- [ ] Distant Worlds 原生过程拒绝时不进入 WORKING；
- [ ] 研究未解锁时不能绕过限制。

### 20.6 能量和燃料

- [ ] 能量充足时不额外提取燃料；
- [ ] 能量不足时找到附近 Lithum Furnace；
- [ ] 只提取 `lithum_furnace_fuel` 标签内物品；
- [ ] 目标燃料槽满时不提取；
- [ ] 目标槽不能接受燃料时不提取；
- [ ] 燃料插入失败时退款；
- [ ] 不直接写 CurrentEnergy；
- [ ] 祭坛由原生过程正常恢复能量；
- [ ] 多个炉子选择稳定，不每 tick 换目标。

### 20.7 完成和输出

- [ ] `CurrentRecipe` 进入 WORKING；
- [ ] `CurrentRecipe` 清空且真实结果存在时完成；
- [ ] 结果槽为空时不补发模板；
- [ ] 结果被管道提前取走时不刷物；
- [ ] 世界掉落被玩家提前拾取时不刷物；
- [ ] 多产物全部进入 RS；
- [ ] 完成后核心/pedestal 临时材料清理正确。

### 20.8 异常路径

- [ ] 核心区块卸载；
- [ ] pedestal 区块卸载；
- [ ] 炉子被破坏；
- [ ] 玩家断线；
- [ ] 服务器停止；
- [ ] 合成计划 abort；
- [ ] 上游步骤已提交、祭坛步骤失败；
- [ ] shared ledger 不发生双倍退款；
- [ ] `COMMITTED` ledger 失败时显式退款。

### 20.9 JEI

- [ ] Lithum Altar 类别显示；
- [ ] 输入位置和数量正确；
- [ ] 输出数量来自真实定义；
- [ ] 能量信息正确；
- [ ] 未知/不支持配方不会出现自动执行按钮；
- [ ] JEI 传输由服务端重新验证；
- [ ] 目标模组不存在时 JEI 插件不崩溃。

---

## 21. 不推荐的实现方式

### 21.1 把 Lithum Altar 当普通 Recipe

错误原因：祭坛配方不由标准 `RecipeManager` 直接驱动。

### 21.2 只写 CurrentRecipe

错误原因：没有材料放置、结构、staff、能源和研究状态验证。

### 21.3 直接填 CurrentEnergy

错误原因：绕过能源网络和燃料消耗，破坏原模组平衡。

### 21.4 扫描第一个 ItemStack 字段当结果

错误原因：核心、pedestal、炉子和结果槽各自都有 ItemStack，字段顺序不稳定。

### 21.5 祭坛完成时用 wrapper 输出补发

错误原因：真实结果可能已被管道、磁铁或玩家取走，补发会复制物品。

### 21.6 只锁 Lithum Core

错误原因：pedestal、炉子和能源结构是邻接共享资源，可能导致串料和并发冲突。

### 21.7 绕过研究条件

错误原因：会让 RS 自动化破坏 Distant Worlds 的研究进度设计。

### 21.8 在 Reflection 探针中缓存 Method/Field

错误原因：违反项目现有反射架构，也增加映射变化和可维护性风险。

---

## 22. 推荐最终架构

```text
JEI
 │
 ▼
LithumAltarRecipeWrapper
 │
 ▼
ModType / RecipeIndex
 │
 ▼
CraftPlanGraph
 │
 ▼
LithumAltarBatchDelegate
 │
 ├── resolve bound Lithum Core
 ├── validate multi-block structure
 ├── resolve Distant Worlds recipe
 ├── reserve RS ingredients
 ├── place core/pedestal materials
 ├── find/consume dalite_staff
 ├── find nearby Lithum Furnace
 ├── refill #distant_worlds:lithum_furnace_fuel
 ├── invoke native start procedure
 ├── observe CurrentRecipe/CurrentEnergy
 ├── capture real result
 └── cleanup/refund
```

状态机：

```text
IDLE
  -> VALIDATING
  -> RESERVED
  -> PLACING
  -> STARTING
  -> WORKING
  -> REFUELING
  -> WORKING
  -> COLLECTING
  -> DONE
```

失败状态：

```text
VALIDATING -> FAILED
RESERVED   -> REFUNDED
PLACING    -> CLEANUP + REFUNDED
STARTING   -> CLEANUP + REFUNDED
WORKING    -> FAILED/CLEANUP
REFUELING  -> REFUNDED 或 RETRY
COLLECTING -> FAIL CLOSED
```

---

## 23. 实现验收标准

在称为“完成”之前，至少满足以下条件：

```text
1. 可以通过 JEI 查看 Lithum Altar 配方
2. 可以绑定 Lithum Core，而不是绑定 pedestal
3. 可以从 RS 正确提取并放置材料
4. 可以使用原生 Distant Worlds 启动逻辑
5. 能量不足时可以安全补附近炉子燃料
6. 不直接伪造 CurrentEnergy
7. 完成判断基于真实 CurrentRecipe 和真实产物
8. 结果被外部取走时不会补发
9. 失败路径不会吞物或刷物
10. 研究条件不会被绕过
11. Distant Worlds 未安装时主模组不崩溃
12. shared ledger、abort、区块卸载路径通过测试
13. 第一版默认保持祭坛执行 exclusive
14. 中文和英文语言键同步
15. `compileJava` 和相关单元测试通过
```

---

## 24. 开发顺序建议

推荐严格按以下顺序推进：

```text
Reflection 探针
  -> ModIds/Config/Module
  -> Core 绑定
  -> Structure Helper
  -> 状态读取
  -> Recipe Definition/Resolver
  -> JEI Wrapper
  -> 材料放置 Delegate
  -> 原生启动
  -> Fuel Helper
  -> 输出捕获
  -> DAG/计划接入
  -> 测试和兼容性
```

其中最关键的工程边界是：

```text
先确认真实输入和真实输出
再接入自动合成
先保证失败可退款
再考虑并发和批量优化
```

Distant Worlds Lithum Altar 的第一版应以“服务端权威、复用原生启动逻辑、燃料通过真实炉子补充、真实结果才算成功”为核心原则。
