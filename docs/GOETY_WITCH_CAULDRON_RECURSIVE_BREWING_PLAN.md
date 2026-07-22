# Goety 女巫坩埚递归炼制集成计划

## 1. 目标与结论

目标是让 RS Integration 能够识别、规划并自动执行 Goety 2.5.53.1 的女巫坩埚炼制，包括：

- JEI 当前展示的效果催化剂；
- JEI 未展示的容量剂、增强剂和形态转换材料；
- 火药、龙息、烈风核心等改变最终药酿类型的材料；
- 红石、萤石及其更高阶材料形成的持续时间、效果等级等增强；
- 多效果、高等级、带完整 NBT 的药酿目标；
- 所有普通物品材料的递归合成；
- 灵魂能量、容量、顺序和机器状态的启动前校验；
- 中止、掉线和外部干预时不吞物品的事务恢复。

核心结论：女巫坩埚不是普通 `Ingredient -> ItemStack` 配方，而是顺序敏感的状态机。高等级药酿也不是“低等级成品再次投入”的递归配方，而是从目标 NBT 反推一锅内的完整材料序列，再让现有 DAG 递归生产这些材料。

## 2. 已验证的 Goety 行为

本计划依据运行版本 `goety-2.5.53.1.jar` 的数据文件、Patchouli 文档和字节码。

### 2.1 原生状态机

正确顺序为：

1. 坩埚装水并加热；
2. 投入下界疣，进入酿造模式；
3. 依次投入容量剂；
4. 投入一个或多个效果催化剂，或提供实体祭品；
5. 依次投入增强剂；
6. 使用坩埚巨勺调用 `BrewCauldronBlockEntity.brew()`；
7. 从附近灵魂烛台扣除 `getBrewCost()`；
8. 等待完成；
9. 使用空瓶或支持的容器取出最终药酿。

错序、超过容量、缺少有效效果或不满足灵魂成本都会使状态进入失败模式。

### 2.2 配方与动态注册

`BrewingRecipe` 仅描述一个效果催化剂：

- `Ingredient input`；
- `MobEffect output`；
- `soulCost`；
- `capacityExtra`；
- `duration`。

此外，`BrewEffects` 在代码中维护：

- 物品到自定义效果的映射；
- 物品到 `BrewModifier` 的映射；
- 实体祭品到效果的映射。

因此不能只扫描 datapack 的 `goety:brewing` 配方，还必须枚举注册物品并调用 `BrewEffects.getEffectFromCatalyst(Item)` 与 `getModifier(Item)`。

### 2.3 JEI 缺失原因

Goety 的 `WitchBrewMaker` 会遍历 JEI 物品并创建 `WitchBrewJeiRecipe`，但该对象只有：

- `catalyst`；
- 单效果示例 `output`；
- `capacity`；
- `soulCost`。

它没有容量剂、增强剂、顺序或完整灵魂成本模型。`WitchBrewCategory` 因而只能展示单个催化剂，火药等增强剂不会成为配方输入。RSI 不能把此 JEI DTO 当作执行配方。

## 3. 支持边界

第一版正式支持：

- 物品催化剂产生的原版与 Goety 药酿效果；
- 多效果组合；
- 容量扩展；
- 持续时间、效果等级、范围、滞留、饮用速度和投掷力度增强；
- 喷溅、滞留、气态和隐藏粒子转换；
- 最终 `brew`、`splash_brew`、`lingering_brew`、`gas_brew` 的精确 NBT 输出；
- 普通材料的 DAG 递归生产。

第一版不自动支持：

- 需要活体实体投入坩埚的祭品效果；
- 依赖玩家穿戴、附近猫、巫术等级等改变装瓶结果的非确定性增益；
- 食物、巫毒娃娃、指路石等非瓶装交付路径；
- 无法从目标 NBT 唯一反解的自定义第三方效果。

这些能力可显示在计划界面，但必须明确标为需要人工步骤，不能伪装成可自动执行。

## 4. 领域模型

新增专用不可变模型，不复用 `WitchBrewJeiRecipe`：

```java
record BrewTarget(
        Item outputItem,
        List<BrewEffectTarget> effects,
        BrewShape shape,
        int durationTier,
        int amplifierTier,
        int aoeTier,
        int lingerTier,
        int quaffTier,
        int velocityTier,
        boolean aquatic,
        boolean fireProof,
        boolean hiddenParticles) {}

record BrewProcess(
        BrewTarget target,
        List<ItemStack> capacitySequence,
        List<BrewCatalyst> catalysts,
        List<ItemStack> modifierSequence,
        int capacityRequired,
        int capacityAvailable,
        int soulCost,
        ItemStack exactOutput) {}
```

`BrewTarget` 的等价性必须基于规范化后的药酿 NBT，而不是显示名。效果列表需要稳定排序，同时保留 Goety 运行时会影响结果的字段。

## 5. 配方发现与索引

新增 `GoetyBrewCatalog`：

1. 扫描 `RecipeManager` 中全部 `BrewingRecipe`；
2. 枚举物品注册表，发现代码注册的效果催化剂与增强剂；
3. 把每个物品分类为 `STARTER`、`CAPACITY`、`CATALYST`、`MODIFIER` 或 `FINISH_CONTAINER`；
4. 记录 modifier 的 ID、等级、成本倍率和顺序约束；
5. 使用 Goety 自身方法生成单步输出，避免复制其 NBT 格式；
6. `/reload` 后清空并重建 catalog。

禁止将每种可能的多效果组合预先展开进 `RecipeIndex`，组合数量会指数增长。索引只登记可选择的单效果模板；目标 NBT 的组合规划应按需进行。

## 6. 目标选择与 JEI 集成

### 6.1 补全原生 JEI

新增 RSI 自己的“女巫坩埚工艺”JEI 类别或扩展页，显示：

- 起始材料：下界疣；
- 容量剂序列；
- 效果催化剂；
- 增强剂序列；
- 最终容器；
- 总容量与总灵魂成本；
- 精确输出。

不要修改 Goety 原生 `WitchBrewJeiRecipe` 对象，也不要通过 mixin 把火药硬塞进其单催化剂槽位；它无法表达顺序和组合。

### 6.2 高等级目标入口

提供两种入口：

- 对 JEI 中某个单效果药酿点击 RSI 合成按钮，默认生成基础等级；
- 对实际带 NBT 的药酿、书签或 RS 网格中的模板请求合成，按其完整 NBT 规划高等级版本。

后续可增加编辑器，让玩家选择效果、等级、时长和形态，但执行核心必须先建立在精确 NBT 模板上。

## 7. 高等级药酿规划算法

### 7.1 不是成品递归

以下链路是错误的：

```text
基础药酿 -> 再投入萤石 -> II 级药酿
```

坩埚接受的是一锅内的有序材料。正确模型是：

```text
目标 II 级喷溅药酿
  -> 下界疣
  -> 必要容量剂
  -> 效果催化剂
  -> 萤石粉或更高阶强度增强剂
  -> 火药
  -> 空瓶
```

这些叶子物品再交给现有 DAG 递归生产。

### 7.2 反向求解

`BrewPlanner.plan(BrewTarget)` 应执行：

1. 从目标 NBT 解出效果集合、持续时间、等级和形态；
2. 为每个效果寻找成本最低且确定性的催化剂；
3. 计算效果占用容量；
4. 选择满足容量的最短合法容量剂前缀；
5. 为每个目标属性选择合法的增强剂等级序列；
6. 按 Goety 原生顺序排序所有步骤；
7. 在纯模拟器中逐步执行，确认不会进入 `FAILED`；
8. 用 Goety 原生 NBT API 生成预测输出；
9. 预测输出必须与目标 `ItemStack` 精确一致；
10. 将全部物品需求作为一个原子 operation 交给 DAG。

若存在多个等价材料，作为候选方案交给现有 resolver 评分，而不是在 planner 内任意选第一个。

## 8. 机器绑定与执行委托

新增 ModType `goety_witch_cauldron`，只允许绑定 `goety:witch_cauldron`。灵魂烛台是支持结构，不是独立机器。

新增 `WitchCauldronBatchDelegate`：

1. 验证方块实体类型、热源、水位、空闲模式和空容器；
2. 验证附近灵魂烛台总能量大于等于预测成本；
3. 在材料提交前，用影子状态机模拟整个序列；
4. 提交 ledger 后按固定顺序逐个调用原生 `insertItem`；
5. 每一步检查返回的 `Mode`，任何异常立即停止；
6. 调用原生 `brew()`，不复刻 tick 或扣魂逻辑；
7. 等待 `isBrewing` 完成且模式稳定；
8. 使用原生装瓶路径，或在确认等价后调用最小取出 API；
9. 对产物做精确 NBT 校验，再交付 RS。

坩埚属于单 operation 独占机器。批量请求可以在多个已绑定坩埚间并行，但一个坩埚同一时间只能运行一锅。

## 9. 事务与防吞物品

该机器会逐步消费世界状态，不能用“启动失败后简单退回全部输入”的假原子模型。

必须记录 `BrewOperationJournal`：

- ledger reservation token；
- 启动前方块实体完整快照；
- 每个已接受材料及其序号；
- 启动是否成功；
- 已扣灵魂量；
- 已生成但尚未交付的输出；
- 装瓶容器消耗。

恢复规则：

- 第一个材料投入前失败：退款全部预留；
- 部分材料投入但尚未启动：只在方块状态仍与 journal 一致时恢复快照并退款；
- 已启动：不得盲目退款材料，继续观察完成或进入人工恢复；
- 已捕获输出：结算输入并交付实际输出；
- 外部玩家改变坩埚：停止自动恢复，记录高严重度日志并保留可领取物品，禁止同时退款和交付。

## 10. 灵魂、容量与环境校验

准备阶段必须报告：

- 当前/需要灵魂；
- 当前/需要容量；
- 缺少热源或水；
- 材料顺序不合法；
- 目标含有不支持的实体祭品；
- 目标受穿戴或附近生物影响，无法保证精确输出。

灵魂成本要用与原生一致的浮点倍率和最终取整规则计算，不能简单累加 JSON 的 `soulCost`。最可靠做法是影子执行同一组 modifier，再读取 `getBrewCost()`。

## 11. 建议代码结构

```text
mods/goety/brewing/
  GoetyBrewCatalog.java
  BrewTarget.java
  BrewProcess.java
  BrewTargetCodec.java
  BrewPlanner.java
  BrewStateSimulator.java
  WitchCauldronBatchDelegate.java
  BrewOperationJournal.java
  WitchCauldronBinding.java

recipe/
  GoetyBrewRecipeHandler.java

compat/jei/goety/
  RsiWitchBrewCategory.java
  RsiWitchBrewRecipe.java
```

所有 Goety 类名、字段和方法集中放入 `GoetyReflection`，启动时做 contract validation；不要在 delegate 中散落字符串反射。

## 12. 测试矩阵

### 12.1 目录与规划测试

- datapack `BrewingRecipe` 催化剂被发现；
- 代码注册催化剂被发现；
- 火药、龙息、烈风核心分类正确；
- 红石和三档效果增强材料映射正确；
- 多效果容量不足时拒绝；
- 容量剂错序时模拟失败；
- 相同效果不同 NBT 不合并；
- 预测输出与 Goety 原生 `getBrew()` 精确一致。

### 12.2 执行与恢复测试

- 基础单效果药酿；
- II/III/IV 档效果增强；
- 延时、范围和投掷增强；
- 喷溅 -> 滞留 -> 气态顺序；
- 多效果高容量药酿；
- 灵魂不足在 ledger commit 前拒绝；
- 投入第 N 个材料时失败不吞物品；
- 启动后断线继续完成或进入可恢复状态；
- 外部玩家取走/投入物品时不复制、不退款已消费材料；
- 多坩埚并行、单坩埚互斥；
- 产物实际 NBT 不符时拒绝结算并保留实物。

## 13. 实施阶段

### 阶段 1：只读目录与模拟器

- 建立 reflection contract；
- 完成催化剂/增强剂目录；
- 完成目标 NBT codec；
- 完成顺序、容量、成本和输出模拟测试。

此阶段不修改世界，是后续安全性的前提。

### 阶段 2：JEI 与计划展示

- 新增 RSI 女巫坩埚类别；
- 展示完整有序输入和精确输出；
- 将目标模板接入计划预览；
- 对祭品或非确定性目标显示不可自动化原因。

### 阶段 3：单锅执行

- 新增绑定目标和 delegate；
- 只支持单效果、无增强基础药酿；
- 完成事务 journal、启动前校验和结果捕获。

### 阶段 4：高等级与多效果

- 接入所有物品 modifier；
- 支持容量扩展、多效果和形态转换；
- 目标 NBT 反向求解；
- 材料需求接入 DAG 递归。

### 阶段 5：批量与并发

- 多绑定坩埚并行；
- 每锅独立 ledger token 和 journal；
- 完成掉线、重启、取消和外部干预测试。

## 14. 验收标准

实现完成必须满足：

1. JEI/RSI 能看到火药等增强剂及其严格顺序；
2. 请求高等级药酿时，计划能递归生产全部普通材料；
3. 预测输出与实际输出的物品、数量和 NBT 完全一致；
4. 灵魂或容量不足时不提取 RS 材料；
5. 任意启动失败、中止或掉线路径不吞物品、不复制物品；
6. 不支持的祭品和非确定性增益必须明确拒绝，不能生成看似可执行的计划；
7. 至少使用 Goety 2.5.53.1 完成基础、增强、多效果、喷溅、滞留和气态各一条真实闭环。
