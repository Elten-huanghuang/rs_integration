# 炽炉（Clibano）RS 绑定与自动合成集成计划

## 一、目标与分析范围

本文档描述 Forbidden & Arcanus 炽炉（Clibano）接入 RS Integration 的设计方案，目标包括：

- 玩家可以用已绑定 RS 网络物品绑定完整炽炉结构；
- 点击任意可交互的炽炉外壳时，绑定统一指向真正的主方块实体；
- RS 侧边栏可以远程打开炽炉原生 UI；
- JEI 中的 `clibano_combustion` 配方可以进入递归合成计划；
- 自动合成可以安全选择两个输入槽中的空闲通道；
- 自动补充燃料，并在需要时补充灵魂火资源；
- 正确识别共享输出槽中的本次产物；
- 合成成功、中止和机器失效时保持物品守恒。

分析目标版本：

```text
libs/forbidden_arcanus-1.20.1-2.2.6.jar
```

主要分析类：

```text
com.stal111.forbidden_arcanus.common.block.ClibanoPart
com.stal111.forbidden_arcanus.common.block.ClibanoMainPartBlock
com.stal111.forbidden_arcanus.common.block.entity.clibano.ClibanoMainBlockEntity
com.stal111.forbidden_arcanus.common.block.entity.clibano.ClibanoItemHandler
com.stal111.forbidden_arcanus.common.block.entity.clibano.ClibanoFireType
com.stal111.forbidden_arcanus.common.inventory.clibano.ClibanoMenu
com.stal111.forbidden_arcanus.common.recipe.ClibanoRecipe
```

---

## 二、结构与主机定位

### 2.1 多方块结构

炽炉组装后为 3×3×3 多方块结构。可见结构由以下方块组成：

```text
ClibanoCenterBlock
ClibanoCoreBlock
ClibanoCornerBlock
ClibanoHorizontalSideBlock
ClibanoVerticalSideBlock
```

这些外壳方块使用：

```text
ClibanoBlockEntity
```

真正保存库存、进度、燃料、火焰类型和配方记录的是隐藏主部件：

```text
ClibanoMainPartBlock
ClibanoMainBlockEntity
```

自动合成和远程 UI 都必须使用 `ClibanoMainBlockEntity` 的位置，不能把某个可见外壳的位置当作机器位置。

### 2.2 模组原生主机解析

四种可交互外壳实现 `ClibanoPart`。`ClibanoCoreBlock` 在 2.2.6 中不实现该接口，因此第一版不将核心方块作为可点击绑定入口。玩家点击其余外壳时，模组调用：

```java
ClibanoPart.findMainPos(level, clickedPos)
```

该方法通过 Clibano 主部件 POI，在点击位置半径 2 内查找主方块，然后由：

```java
ClibanoPart.openScreen(...)
```

取得 `ClibanoMainBlockEntity` 并调用：

```java
NetworkHooks.openScreen(player, mainBlockEntity, mainPos);
```

因此绑定逻辑应复用 `findMainPos()`，不应自行根据结构朝向计算偏移。

### 2.3 BindingEventHandler 改造

当前 `BindingEventHandler.resolveRootPos()` 已处理 TACZ、TLM 祭坛和 YHK 蒸锅，但尚未处理 Clibano。

应增加 Clibano 分支：

1. 识别方块是否实现 `ClibanoPart`；
2. 调用 `findMainPos(level, clickedPos)`；
3. 验证返回位置存在 `ClibanoMainBlockEntity`；
4. 将该位置保存为绑定位置；
5. 后续使用主方块的 block key、registry key 和显示图标。

绑定必须满足以下不变量：

```text
同一座炽炉无论点击哪个外壳，最终只产生一个规范化绑定位置。
```

---

## 三、库存和槽位模型

`ClibanoMenu.SLOT_COUNT` 为 7。槽位分布如下：

| 槽位 | 名称 | 用途 |
|---:|---|---|
| 0 | Enhancer | 增强器槽，不是普通配方原料 |
| 1 | Soul | 灵魂槽，用于切换或维持高级火焰 |
| 2 | Fuel | 普通燃料槽 |
| 3 | Input A | 第一条独立加工通道 |
| 4 | Input B | 第二条独立加工通道 |
| 5 | Output A | 共享输出池的第一槽 |
| 6 | Output B | 共享输出池的第二槽 |

对应常量：

```java
ENHANCER_SLOT = 0;
SOUL_SLOT = 1;
FUEL_SLOT = 2;
INPUT_SLOTS = Pair.of(3, 4);
RESULT_SLOTS = Pair.of(5, 6);
```

### 3.1 双输入的真实语义

Clibano 配方继承 `AbstractCookingRecipe`，每个配方只有一个 `ingredient`。两个输入槽不是一个配方的两个材料，而是两条可以独立工作的加工通道：

```text
槽 3 -> 配方 A -> cookingProgressFirst
槽 4 -> 配方 B -> cookingProgressSecond
```

两个输入槽可以同时加工不同配方，也可以加工相同配方。

自动合成每次执行一个 `ClibanoRecipe` 时，只应：

- 选择槽 3 或槽 4 中的一条空闲通道；
- 放入一份该配方的输入；
- 记录被分配的输入槽；
- 只跟踪该通道对应的进度和输入消耗。

禁止把一份配方材料同时放入两个输入槽，否则会执行两次配方并产生双倍产物。

### 3.2 独立进度

主方块实体保存两套进度：

```text
CookingProgressFirst
CookingProgressSecond
CookingDurationFirst
CookingDurationSecond
```

这些值会写入方块实体 NBT。第一版可以通过保存 NBT 读取进度，不必反射访问 private 字段，也不应依赖字段的运行时名称。

---

## 四、共享输出池

### 4.1 输出没有固定通道对应关系

不能假设：

```text
Input A -> Output A
Input B -> Output B
```

`ClibanoMainBlockEntity.finishRecipe()` 的行为是：

1. 尝试堆叠到槽 5；
2. 槽 5 不匹配或容量不足时尝试槽 6；
3. 两个槽都空时优先使用槽 5；
4. 槽 5 已被其他产物占用时可能使用槽 6。

槽 5 和槽 6因此构成共享输出池。

### 4.2 第一阶段安全策略

第一阶段应在启动新操作前，将槽 5、6中的既有产物安全转存到当前绑定的 RS 网络。输出槽非空本身不应阻止自动合成；只有无法完整排空时才拒绝启动。

排空必须采用事务式顺序：

1. 读取槽 5、6的完整快照；
2. 使用 `Action.SIMULATE` 验证 RS 能完整接收两个槽的全部物品；
3. 任一物品无法完整接收时，不修改机器并拒绝启动；
4. 模拟全部成功后，从机器实际提取物品；
5. 立即使用 `Action.PERFORM` 插入同一个 RS 网络；
6. 若模拟与实际插入之间发生状态变化，未插入余量必须恢复到原槽；无法恢复时交给玩家或掉落在机器位置，禁止 void；
7. 排空完成后再次确认槽 5、6为空，再提交本次配方输入。

这样既能维持自动化体验，也能建立清晰的 operation 边界：

```text
排空前的槽 5/6物品 -> 既有机器产物，转存到 RS
排空后的新产物      -> 当前合成 operation 的产物
```

完成后应：

1. 在槽 5、6中查找与配方结果严格匹配的 ItemStack；
2. 合计本次预期产物数量；
3. 只提取需要的数量；
4. 不提取轮询期间被外部系统放入的不匹配物品；
5. 将实际提取结果交给异步合成链的虚拟库存。

### 4.3 后续增量提取策略

第一阶段在启动前排空输出，因此本次 operation 的产物边界明确。若以后允许不排空输出直接启动，则必须基于快照计算增量：

```text
本次可提取数量 = 完成后目标产物总数 - 启动前目标产物总数
```

需要同时处理：

- 目标物品分布在两个输出槽；
- 产物堆叠到已有同类物品；
- 外部管道在轮询期间抽走产物；
- 两个并发通道产出相同物品。

在没有 operation 级产物归属机制前，不应启用不排空输出的宽松模式。

---

## 五、火焰类型与灵魂资源

Clibano 有三种火焰类型：

| 类型 | 等级 | 烹饪速度 | 来源 |
|---|---:|---:|---|
| `FIRE` | 0 | 1.0× | 默认状态 |
| `SOUL_FIRE` | 1 | 1.5× | `soul` 或 `corrupt_soul` |
| `ENCHANTED_FIRE` | 2 | 2.5× | `enchanted_soul` |

相关物品标签：

```text
forbidden_arcanus:clibano/creates_soul_fire
forbidden_arcanus:clibano/creates_enchanted_fire
```

灵魂资源位于槽 1。模组在需要加工且检测到更高等级灵魂物品时消耗一个物品，并将强化火焰维持：

```text
2700 ticks
```

### 5.1 配方火焰要求

`ClibanoRecipe` 提供：

```java
getRequiredFireType()
```

原生判断等价于：

```java
currentFireType.ordinal() >= requiredFireType.ordinal()
```

高级火焰可以执行低等级配方。

### 5.2 自动合成策略

启动前应：

1. 读取配方要求的火焰等级；
2. 读取机器当前火焰等级；
3. 当前等级足够时不动槽 1；
4. 当前等级不足时，在槽 1已有合法灵魂物品的情况下优先沿用；
5. 槽 1为空时，从 RS 提取满足最低要求的灵魂物品；
6. 槽 1被无关物品占用时拒绝启动；
7. 记录本操作实际放入槽 1的物品和数量。

灵魂物品属于机器运行资源，不应作为普通 recipe ingredient 展示或重复计入材料需求。

当前 jar 内置配方未发现需要特殊火焰的样例，但 datapack 可以扩展该字段，因此实现不能只覆盖默认火焰。

---

## 六、燃料

槽 2为燃料槽。Clibano 使用自身的：

```java
getBurnDuration(ItemStack)
```

计算燃烧时间。

自动合成不能假设一个燃料足够完成一次加工，也不能在结束时直接清空整个燃料槽，否则会把玩家预先放入的燃料错误归属给当前操作。

### 6.1 推荐策略

启动前记录：

```text
fuelBefore
```

随后：

1. 如果槽 2已有合法燃料，保留并使用；
2. 如果槽 2为空，从 RS 选择合法燃料；
3. 提取能覆盖预计烹饪时间的最小合理数量；
4. 无法准确计算时采用保守补充上限；
5. 记录当前操作额外放入的燃料数量；
6. 完成或中止时，只回收当前操作未消耗的增量；
7. 不移动玩家启动前已有的燃料。

第一版若无法可靠区分燃料增量，可采用更严格的前置条件：

```text
要求燃料槽为空，由当前操作独占并管理燃料槽。
```

这比在共享燃料槽中错误退款更安全。

---

## 七、Forge Item Handler 方向语义

`ClibanoMainBlockEntity` 针对不同方向暴露不同的 `IItemHandler`：

| 方向 | 行为 |
|---|---|
| `UP` | 允许向槽 3、4插入输入 |
| 水平侧面 | 允许向槽 1、2插入灵魂或燃料 |
| `DOWN` | 允许从槽 5、6提取输出 |

`ClibanoItemHandler` 会拒绝不符合方向规则的插入和提取。

实现可选择两种访问方式：

### 方案 A：使用方向能力

```java
be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP)
be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH)
be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN)
```

优点：

- 遵循模组公开自动化契约；
- 自动获得槽位访问限制。

缺点：

- 读取完整库存和执行精准清理时需要维护三个 handler；
- 水平方向与结构朝向无关，但仍需选择一个方向。

### 方案 B：使用主方块实体公共库存 API

```java
getItemStackHandler()
getStack(int)
setStack(int, ItemStack)
```

优点：

- 可以精准操作 7 个槽；
- 更适合事务清理和快照恢复。

缺点：

- 与 Valhelsia 容器基类 API 耦合；
- 必须自行遵守槽位规则。

推荐使用公共库存 API进行读写和回滚，同时用方向能力的 `isItemValid` 或模拟插入验证物品是否合法。不得反射调用原版或模组运行时可能被重映射的方法名。

---

## 八、配方分类与 JEI

### 8.1 现有冲突

当前 `FaRSModule` 将整个包前缀注册为 Hephaestus Forge 类型：

```java
ModType.register("forbidden_arcanus",
        new String[]{"com.stal111.forbidden_arcanus."},
        ...,
        FaBatchDelegate);
```

这会让 `ClibanoRecipe` 也被分类为 `FaBatchDelegate`，而该 delegate 只支持 Hephaestus Forge 仪式。

### 8.2 独立 ModType

应注册更具体的类型：

```java
ModType.register("forbidden_arcanus_clibano",
        new String[]{
                "com.stal111.forbidden_arcanus.common.recipe.ClibanoRecipe"
        },
        new String[]{"clibano"},
        new String[]{"forbidden_arcanus_clibano"},
        ModType.delegateSupplier(
                "com.huanghuang.rsintegration.mods.forbidden.ClibanoBatchDelegate"));
```

`ModType.classifyRecipe()` 使用最长前缀匹配，因此专用 Clibano 类型会优先于宽泛的 Forbidden & Arcanus 类型。

### 8.3 JEI 配置

JEI 分类 UID：

```text
forbidden_arcanus:clibano_combustion
```

建议配置：

```java
ModType.configureJei("forbidden_arcanus_clibano",
        new String[][]{
                {"forbidden_arcanus:clibano_combustion", "forbidden_arcanus_clibano"}
        },
        new String[][]{
                {"com.stal111.forbidden_arcanus.common.recipe.ClibanoRecipe",
                        "forbidden_arcanus_clibano"}
        },
        "gui.rs_integration.jei.clibano_craft");
```

### 8.4 RecipeHandler

`ClibanoRecipe` 继承 `AbstractCookingRecipe`，基础输入与输出可以通过标准 API获得：

```java
recipe.getIngredients()
recipe.getResultItem(registryAccess)
```

可以新增 `ClibanoRecipeHandler`，用于：

- 精确声明只处理 `ClibanoRecipe`；
- 获取一个输入材料；
- 获取主产物；
- 提供 required fire type；
- 提供计划阶段火焰资源警告；
- 明确不把残渣当作普通副产物。

现有 `FaRecipeHandler.canHandle()` 同样使用宽泛包前缀。注册顺序必须保证 Clibano handler 优先，或者收窄 `FaRecipeHandler` 的处理范围。

---

## 九、绑定目标与远程 UI

### 9.1 绑定目标

`FaRSModule.registerBindingTargets()` 应为 Clibano 注册独立目标，覆盖四种可交互外壳，并额外注册隐藏主方块用于规范化位置后的 GUI 能力识别：

```text
ClibanoCenterBlock
ClibanoCornerBlock
ClibanoHorizontalSideBlock
ClibanoVerticalSideBlock
ClibanoMainPartBlock
```

`ClibanoCoreBlock` 不实现 `ClibanoPart`，第一版不作为绑定入口。

建议 block key：

```text
forbidden_arcanus_clibano||block.forbidden_arcanus.clibano_main_part
```

显示图标可映射到：

```text
forbidden_arcanus:clibano_core
```

因为隐藏主方块可能没有适合展示的物品模型，而 `clibano_core` 是玩家实际认识的结构核心物品。

### 9.2 远程打开 UI

`ClibanoMainBlockEntity` 实现 `MenuProvider`。只要绑定位置已经规范化为主方块位置，现有：

```java
BlockGuiRegistry.openGui(player, dim, mainPos)
```

会在 `Block.use()` 不处理时回退到：

```java
StandardMenuProviderOpener.INSTANCE
```

最终通过：

```java
NetworkHooks.openScreen(player, provider, mainPos)
```

打开原生 UI。

第一版不需要专用 `IMachineGuiOpener`，但必须验证远程容器距离检查已经被 `RemoteGuiAuth` 正确放行。

---

## 十、ClibanoBatchDelegate 设计

### 10.1 主要状态

建议实例状态：

```java
private ServerPlayer player;
private ServerLevel level;
private ResourceKey<Level> dimension;
private BlockPos mainPos;
private ClibanoMainBlockEntity clibano;
private ClibanoRecipe recipe;
private ItemStack expectedOutput;
private int assignedInputSlot;
private int inputCountBefore;
private ItemStack output5Before;
private ItemStack output6Before;
private ItemStack fuelBefore;
private ItemStack soulBefore;
private boolean inputSeen;
private boolean progressSeen;
```

如果保持反射隔离，则 `clibano` 和 `recipe` 可以声明为 `Object`，并把类与公共方法集中注册到 `FAReflection`。

### 10.2 validateAndInit

验证流程：

1. 解析绑定维度并加载主方块区块；
2. 按 recipe ID从 RecipeManager 获取配方；
3. 验证配方类为 `ClibanoRecipe`；
4. 验证绑定位置存在 `ClibanoMainBlockEntity`；
5. 验证结构仍然完整；
6. 验证至少一个输入槽为空；
7. 将槽 5、6的既有产物事务式转存到 RS，并验证两个输出槽均为空；
8. 验证槽 1不会阻止要求的火焰类型；
9. 验证槽 2为空或含合法燃料；
10. 保存 expected output 和机器位置。

不能只验证绑定位置的方块 ID，因为结构拆毁后外壳和主方块的状态可能不同步。最终必须以主方块实体存在且未 removed 为准。

### 10.3 getRequiredMaterials

只返回配方的一个普通输入：

```java
List.of(new IngredientSpec(recipe.getIngredients().get(0), 1))
```

不要包含：

- 燃料；
- 灵魂物品；
- enhancer；
- 随机残渣。

这些属于执行资源或机器内部状态，不是配方图上的普通输入边。

### 10.4 tryStartWithMaterials

建议顺序：

1. 重新解析并验证当前主方块实体；
2. 选择空闲输入槽 3或4；
3. 保存所有相关槽位快照；
4. 验证输出槽仍为空；
5. 验证输入材料与 recipe ingredient 匹配；
6. 处理所需灵魂资源；
7. 处理燃料；
8. 将一份已由 chain commit 的输入放入已分配输入槽；
9. 调用 `setChanged()`；
10. 调用 `markCraftStarted()`；
11. 记录 operation 所拥有的物理物品增量。

shared ledger 在调用 delegate 之前已经由 chain commit。delegate 不得再次 commit、refund、reset 或 close；物理放置失败时只清理本操作部署的物品和运行资源，由 chain 执行唯一退款。

### 10.5 observeMachineCraft

第一阶段状态机：

#### WAITING_FOR_START

满足以下任一条件后进入 WORKING：

- 对应通道的进度大于 0；
- 输入仍存在且机器开始燃烧；
- 输入已经消耗且目标输出已经出现。

不能因为输入刚放入后进度仍为 0就判定失败。

#### WORKING

成功条件：

```text
指定输入槽相对启动前消耗一份输入
且输出槽 5/6 中出现足量目标产物
```

异常条件：

- 主方块实体消失：失败；
- 指定输入被替换成不匹配物品：失败；
- 输入消失但产物在宽限期内未出现：失败；
- 输出槽被不兼容物品占满：失败；
- 所需火焰等级在开始前始终无法达到：失败或超时。

完成检测不得只使用“输入槽为空”，否则玩家或外部管道取走输入时会伪装成成功。

### 10.6 collectAllResults

遍历槽 5、6：

1. 找到与 expected output 完整匹配的堆栈；
2. 提取最多 expected count；
3. 合并为结果列表；
4. 不提取其他物品；
5. 调用 `setChanged()`；
6. 返回实际提取结果。

建议覆盖 `collectAllResults()`，不要依赖单个 `collectResult()`，因为目标产物可能横跨两个输出槽。

### 10.7 clearMachineState

中止清理必须以 operation 所有权为边界：

- 清除当前操作放入的输入残留；
- shared ledger 路径不重复把输入退款到 RS；
- 回收当前操作额外放入且未消耗的燃料；
- 回收当前操作额外放入且未消耗的灵魂物品；
- 不清除槽 0 enhancer；
- 不清除启动前已有燃料或灵魂物品；
- 不清除与当前 expected output 不匹配的输出；
- 对已经完成产生的目标产物，交由 chain-global 守恒逻辑处理，而不是直接 void。

需要明确区分：

```text
pre-existing machine inventory
operation-owned physical input
operation-owned unconsumed runtime resources
committed output
```

### 10.8 onBatchFinished

成功结束时：

- 释放强制区块加载；
- 按所有权退还多余运行资源；
- 清理快照与临时状态；
- 保留 enhancer、残渣和玩家原有库存；
- 不清空另一个输入通道。

---

## 十一、并发策略

### 11.1 第一阶段

第一阶段应声明：

```text
同一 Clibano 同时只允许一个 RS Integration operation。
```

即使机器有两个输入槽，也先按机器级 exclusivity 处理。

原因：

- 两个输入共享输出槽；
- 两个输入共享燃料槽；
- 两个输入共享灵魂槽和火焰类型；
- 两个输入共享残渣存储；
- 相同产物并发时无法仅凭输出增量归属 operation。

空闲输入槽选择仍然有价值：玩家可能正在手动使用一个通道，RS 可以在满足独占策略和输出安全条件时使用另一个通道。但第一版更保守的方案是检测任一通道工作时拒绝启动。

### 11.2 后续双通道并发

后续要开放容量 2，需要引入通道级资源：

```text
Clibano lane 3
Clibano lane 4
Clibano shared output pool
Clibano shared fire/fuel resource
```

每个 operation 必须记录：

- 分配通道；
- 通道进度边沿；
- 输入消耗时刻；
- 输出前后快照；
- 目标产物；
- 燃料和灵魂增量；
- 实际收集数量。

若两个 operation 产出相同物品，还需要按通道完成事件或拦截 `finishRecipe()` 建立明确产物归属。没有该能力时不得仅通过总输出增量猜测归属。

---

## 十二、失败场景与守恒要求

必须覆盖以下场景：

### 12.1 启动前失败

- 配方不存在；
- 绑定不是 Clibano 主机；
- 结构已拆毁；
- 两个输入槽都被占用；
- 输出槽既有产物无法完整转存到 RS；
- 燃料槽状态不允许自动管理；
- 灵魂槽状态不能满足配方；
- RS 材料不足。

要求：不提交 ledger，不修改机器。

### 12.2 提交后放置失败

- 输入槽在提交和放置之间被占用；
- 主方块实体被移除；
- 运行资源插入失败；
- 输入实际未进入目标槽。

要求：清理本操作物理增量并退款 ledger。

### 12.3 加工中失败

- 区块卸载；
- 结构被拆除；
- 输入被玩家或管道取走；
- 输出被不兼容物品堵塞；
- 燃料耗尽且无法补充；
- 火焰等级不足；
- 超时。

要求：

- 已提交但未消耗的输入可恢复；
- 已消耗且已形成产物的步骤不能同时退款输入并保留产物；
- 只清理 operation 自己放入的物品；
- 不修改另一输入通道和玩家原有物品。

### 12.4 完成后收集失败

- 目标产物被外部管道抢先抽走；
- 目标产物不足；
- 目标产物被拆分到两个输出槽；
- 输出在收集前被替换。

要求：实际收集数量必须参与 production audit，不能按 recipe expected output 凭空补齐。

---

## 十三、测试计划

### 13.1 纯逻辑单元测试

建议把以下逻辑提取为无 BlockEntity 依赖的辅助类：

- 空闲输入槽选择；
- 火焰等级比较；
- 两个输出槽的目标产物计数；
- 启动前后输出增量计算；
- 跨两个槽提取指定数量；
- 运行资源增量归属；
- 中止时应清理/应保留槽位判定。

测试用例：

1. 槽 3空、槽 4占用时选择槽 3；
2. 槽 3占用、槽 4空时选择槽 4；
3. 两槽都占用时拒绝；
4. 目标产物只在槽 5；
5. 目标产物只在槽 6；
6. 目标产物横跨槽 5、6；
7. 槽 5含其他物品时不误提取；
8. 启动前已有同类物品时只计算增量；
9. `ENCHANTED_FIRE` 满足 `SOUL_FIRE` 配方；
10. `FIRE` 不满足 `SOUL_FIRE` 配方；
11. 中止不清除 enhancer；
12. 中止不退还启动前已有燃料。

### 13.2 游戏内验证

至少验证：

1. 点击五类外壳均绑定到同一主机位置；
2. 重复点击不同外壳可以正确解绑同一绑定；
3. 侧边栏图标和名称正确；
4. 跨维度远程打开原生 Clibano UI；
5. JEI 按钮只匹配 Clibano 绑定，不匹配 Hephaestus Forge；
6. 槽 3空闲时完成一次普通配方；
7. 槽 4空闲时完成一次普通配方；
8. 输出落在槽 5时正确收集；
9. 输出落在槽 6时正确收集；
10. 输出槽已有产物时先完整转存到 RS；RS 容量不足时拒绝启动且机器物品不变；
11. 缺燃料时提示明确且不扣输入；
12. 需要灵魂火的 datapack 配方可以自动补充灵魂资源；
13. 中途拆除结构后中止且物品守恒；
14. 中途退出服务器后恢复或中止路径守恒；
15. 另一个输入槽已有玩家物品时不会被清理；
16. 外部漏斗抽取输出时不会凭空补发结果。

---

## 十四、预计代码改动

### 新增

```text
src/main/java/com/huanghuang/rsintegration/mods/forbidden/ClibanoBatchDelegate.java
src/main/java/com/huanghuang/rsintegration/recipe/ClibanoRecipeHandler.java
```

可选新增：

```text
src/main/java/com/huanghuang/rsintegration/mods/forbidden/ClibanoInventoryLogic.java
src/test/java/com/huanghuang/rsintegration/mods/forbidden/ClibanoInventoryLogicTest.java
```

### 修改

```text
src/main/java/com/huanghuang/rsintegration/mods/forbidden/FaRSModule.java
src/main/java/com/huanghuang/rsintegration/network/binding/BindingEventHandler.java
src/main/java/com/huanghuang/rsintegration/reflection/probes/FAReflection.java
src/main/resources/assets/rs_integration/lang/zh_cn.json
src/main/resources/assets/rs_integration/lang/en_us.json
```

可能还需要修改：

```text
src/main/java/com/huanghuang/rsintegration/crafting/plan/PlanWarnings.java
src/main/java/com/huanghuang/rsintegration/sidepanel/network/OpenBoundMachineGuiPacket.java
```

只有在需要 Clibano 专用预填或计划阶段运行资源提示时才修改后两项。

---

## 十五、实施顺序

### 阶段 1：绑定和 UI

1. 注册独立 Clibano `ModType`；
2. 注册所有 Clibano 外壳绑定目标；
3. 实现外壳到主机的 root position 解析；
4. 配置名称和图标；
5. 验证远程打开原生 UI。

### 阶段 2：串行自动合成

1. 新增 Clibano recipe handler；
2. 新增 `ClibanoBatchDelegate`；
3. 支持选择单个空闲输入槽；
4. 第一版要求输出槽为空；
5. 支持自动燃料；
6. 支持 required fire type；
7. 实现完成检测和双输出收集；
8. 实现中止清理与 operation 所有权。

### 阶段 3：验证与打磨

1. 增加纯逻辑测试；
2. 进行游戏内守恒测试；
3. 校正失败原因和本地化提示；
4. 检查 DAG capability gate 是否将 Clibano 强制为机器级 exclusivity；
5. 审核超时、离线和跨维度路径。

### 阶段 4：可选双通道并发

只有串行实现长期稳定后再进行：

1. 引入 lane 级 operation resource；
2. 为共享输出建立产物归属；
3. 为共享燃料和火焰资源建立协调；
4. 增加相同产物并发验证；
5. 将机器容量从 1提升为 2。

---

## 十六、第一版验收标准

第一版完成的最低标准：

- 任意 Clibano 外壳都能绑定，且保存主方块位置；
- 侧边栏可以远程打开原生 UI；
- `clibano_combustion` 配方不会再被 `FaBatchDelegate` 接管；
- 自动合成只使用一个输入槽；
- 不会把一个配方输入复制到两个通道；
- 不假设输入和输出槽一一对应；
- 启动前将输出槽既有产物完整转存到 RS，无法完整转存时保持机器不变并拒绝启动；
- 燃料和灵魂资源不足时在扣输入前失败；
- 成功时只收集实际生成的目标产物；
- 中止时不清除 enhancer、另一个输入通道或玩家原有燃料；
- 结构拆毁、超时和玩家离线路径不丢物、不刷物；
- 同一 Clibano 暂时只允许一个 RS operation。

满足以上条件后，Clibano 可以作为可靠的串行多方块机器接入现有递归合成与 DAG 执行框架。
