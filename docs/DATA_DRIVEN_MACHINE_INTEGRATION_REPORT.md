# 数据驱动机器集成方案报告

配套的可复制定义与 Farmer's Delight 对照示例见
[`DATA_DRIVEN_MACHINE_TEMPLATE_FARMERS_DELIGHT.md`](DATA_DRIVEN_MACHINE_TEMPLATE_FARMERS_DELIGHT.md)。

## 结论

实现一套数据驱动机器层后，RS Integration 可以支持一批此前没有专用 Java 模块的全新模组机器，只要这些机器遵守稳定的“物品输入 -> 机器处理 -> 物品输出”契约。

但这不是现有 `customGuiMachineMods` 配置的自然延伸：

- `customGuiMachineMods` 只负责按模组 ID 放开远程 GUI。
- `MachineBindingTarget` 负责绑定目标识别、Machine Hub 和远程打开。
- 递归合成需要额外的配方识别、输入输出语义、机器执行和完成判定。
- 仅有 JSON/KubeJS 配方数据，不能自动推导任意机器的槽位、启动方式和完成条件。

因此推荐新增一个通用的 `DataDrivenMachineDefinition` 层，由 JSON 和 KubeJS 共同生成同一种内部定义；Java 只实现一次通用执行器，复杂机器继续使用专用模块。

## 当前代码能力

### 已经可以数据化的部分

普通工作台配方目前已经有通用路径：

- `GenericRecipeHandler` 读取标准 `CraftingRecipe` 的输入和输出。
- `KubeJsCraftingSemantics` 读取 KubeJS 的输入动作，支持消耗、容器返还、催化剂、工具损耗和阶段限制。
- `GenericBatchDelegate` 可以从 RS 网络预留材料、执行逻辑配方并回收产物。

这条路径适合普通工作台或已经有现成模块支持的配方类型。

### 当前不能只靠 JSON/KubeJS 完成的部分

全新的实体机器仍需要 Java 适配，原因是以下信息不属于标准配方 JSON：

- 哪个方块是机器本体；
- 输入和输出槽位编号；
- 是否需要主动点击或发送特殊数据包启动；
- 如何提供能源、流体、催化剂或其他资源；
- 如何判断一轮加工完成；
- 机器是否允许并行或批量处理；
- 输出是否继承输入 NBT；
- 机器结构是否为多方块。

现有 `GenericBatchDelegate` 不能直接替代实体机器 delegate。它主要执行逻辑配方并把结果放回网络，不会自动把材料塞进任意机器、等待机器进度或读取机器输出。

## 目标架构

### 定义对象

建议新增一个内部定义对象，至少包含以下字段：

```java
record DataDrivenMachineDefinition(
        ResourceLocation id,
        String requiredMod,
        List<ResourceLocation> blocks,
        List<ResourceLocation> recipeTypes,
        List<Integer> inputSlots,
        List<Integer> outputSlots,
        List<Integer> catalystSlots,
        List<Integer> fuelSlots,
        CompletionMode completion,
        StartMode start,
        int timeoutTicks,
        boolean remoteGui,
        boolean parallelSafe
) {}
```

实现时不必严格采用这个 record，但职责应保持分离：机器识别、配方识别、槽位映射和运行策略不能混在模组 ID 判断中。

### 推荐 JSON 格式

```json
{
  "id": "examplemod:crusher",
  "requiredMod": "examplemod",
  "blocks": ["examplemod:crusher"],
  "recipeTypes": ["examplemod:crushing"],
  "inventory": {
    "inputSlots": [0],
    "outputSlots": [1],
    "catalystSlots": [],
    "fuelSlots": []
  },
  "execution": {
    "start": "insert",
    "completion": "expected_output",
    "timeoutTicks": 1200,
    "parallelSafe": false
  },
  "remoteGui": true
}
```

字段含义：

| 字段 | 作用 |
|---|---|
| `id` | RSI 内部机器定义 ID，必须唯一 |
| `requiredMod` | 目标模组未加载时禁用该定义 |
| `blocks` | 精确匹配的机器方块注册名 |
| `recipeTypes` | 可由该机器执行的配方类型 |
| `inputSlots` | 可放入普通输入的槽位 |
| `outputSlots` | 产物读取槽位 |
| `catalystSlots` | 不消耗或按规则返还的槽位 |
| `fuelSlots` | 燃料或能源物品槽位 |
| `start` | 启动方式，例如 `insert`、`use`、`packet` |
| `completion` | 完成判定，例如 `expected_output`、`progress_zero` |
| `timeoutTicks` | 单次加工超时上限 |
| `parallelSafe` | 是否允许多个执行器同时使用同类机器 |
| `remoteGui` | 是否允许绑定并远程打开 GUI |

## KubeJS 接口建议

KubeJS 不应直接创建 Java delegate，而应注册与 JSON 相同的定义：

```javascript
RSIntegrationEvents.machines(event => {
  event.register({
    id: 'examplemod:crusher',
    requiredMod: 'examplemod',
    blocks: ['examplemod:crusher'],
    recipeTypes: ['examplemod:crushing'],
    inputSlots: [0],
    outputSlots: [1],
    completion: 'expected_output',
    timeoutTicks: 1200,
    remoteGui: true
  })
})
```

配方本身仍建议由目标模组或 KubeJS 的原生配方事件创建。例如：

```javascript
ServerEvents.recipes(event => {
  event.custom({
    type: 'examplemod:crushing',
    ingredient: { item: 'minecraft:cobblestone' },
    result: { item: 'minecraft:gravel', count: 1 }
  })
})
```

机器定义和配方定义是两个不同层次：前者描述“在哪里执行”，后者描述“输入输出是什么”。

## 通用执行流程

数据驱动 delegate 应按以下顺序工作：

1. 根据配方类型找到机器定义。
2. 根据绑定记录筛选可用机器方块。
3. 解析输入 `IngredientSpec` 和确定性输出。
4. 从 RS 网络预留并提交输入材料。
5. 将材料按 `inputSlots` 放入机器。
6. 按 `start` 策略启动机器。
7. 每 tick 检查输出、进度或机器状态。
8. 识别目标产物，处理副产物和容器返还。
9. 将产物回收到 RS 网络。
10. 超时、机器消失、输出槽冲突或状态不匹配时退款并结束任务。

执行器必须复用现有的 `ExtractionLedger`、共享存储 endpoint、超时和失败退款逻辑，不能自行实现一套不一致的扣料流程。

## 可支持范围

### 适合只用 JSON/KubeJS 的机器

- 固定方块 ID；
- 固定物品槽位；
- 输入为 `Ingredient` 或确定的物品栈；
- 输出确定且可从配方静态读取；
- 放入输入后自动开始；
- 输出槽出现目标物即可判断完成；
- 无动态 NBT 或随机输出；
- 无复杂能源、流体或多方块要求。

这类机器可以完全由数据定义支持，即使 RSI 之前从未见过该模组。

### 仍需要专用 Java 模块的机器

- 随机输出、概率副产物或结果依赖世界状态；
- 输出需要继承输入 NBT、附魔、耐久或能力数据；
- 输入输出槽位会动态改变；
- 需要流体、能源、法术源、温度、压力等非物品资源；
- 需要多方块结构、相邻方块或特定朝向；
- 通过复杂自定义 API 或网络包启动；
- 需要玩家确认、按钮选择或交互顺序；
- 一个配方有多个动态结果；
- 机器加工过程会改变配方或机器本身状态。

这些机器仍可以复用数据驱动定义中的基础接口，但必须提供自定义 handler 或 delegate。

## 递归合成和远程 GUI 的关系

三种能力应保持独立：

| 能力 | 当前/目标入口 | 是否自动拥有递归合成 |
|---|---|---|
| 绑定机器 | `MachineBindingTarget` | 否 |
| 远程打开 GUI | `remoteGui` / `supportsGui` | 否 |
| 递归合成 | `ModType` + handler + delegate | 是，但必须有配方和执行契约 |

因此：

- 仅注册 `MachineBindingTarget` 不会生成 JEI 递归合成加号。
- 仅把模组加入 `customGuiMachineMods` 也不会生成批量合成能力。
- 数据驱动定义只有在绑定了配方类型和执行策略后，才应让配方进入递归合成索引。

## 安全和校验要求

数据定义来自 JSON/KubeJS，加载时必须拒绝不完整或危险配置：

- `id`、方块 ID 和配方类型必须是合法 `ResourceLocation`；
- 所有槽位必须非负且去重；
- 输入槽和输出槽不能重叠；
- `timeoutTicks` 必须有上限；
- `completion` 和 `start` 只能使用白名单枚举；
- 目标模组未加载时不注册机器类型；
- 找不到可验证输入或确定性输出时不进入递归索引；
- `parallelSafe=true` 只能用于经过并发验证的机器；
- 远程 GUI 打开仍需经过绑定、区块加载、保护检查和现有远程菜单授权。

## 推荐实施阶段

### 第一阶段：标准物品机器

只支持 `IItemHandler` 或标准容器的固定物品输入输出机器：

- JSON reload listener；
- `DataDrivenMachineRegistry`；
- 通用 `DataDrivenRecipeHandler`；
- 通用 `DataDrivenMachineBatchDelegate`；
- `expected_output` 完成判定；
- JSON/KubeJS 定义校验；
- JEI 配方类型映射。

### 第二阶段：启动和状态适配

增加可配置的：

- 点击启动；
- 自定义启动包；
- 进度字段读取；
- 燃料槽；
- 简单副产物和容器返还。

### 第三阶段：复杂资源和专用模块

为能源、流体、多方块、动态 NBT 和随机结果提供明确的 Java 扩展点，不把这些语义强行塞进 JSON。

## 验收标准

一个全新的标准机器模组接入后，应满足：

1. 不修改 RSI 的模组专用 Java 类。
2. 只通过 JSON 或 KubeJS 声明机器和配方。
3. 能绑定机器并在 Machine Hub 中显示。
4. JEI 中出现递归合成入口，且只对该配方类型出现。
5. 缺料时能递归生产中间材料。
6. 执行成功后产物进入 RS 网络。
7. 机器超时或被破坏时材料正确退款。
8. 无绑定机器、机器模组未加载或配置非法时不显示可执行入口。
9. 复杂或无法验证的机器被拒绝，而不是静默产生错误产物。

## 最终判断

实现该数据驱动层后，**全新的模组机器可以通过 JSON/KubeJS 接入，但仅限于标准、确定性、物品槽驱动的机器**。远程 GUI、配方索引和真实机器执行必须分别建模；`customGuiMachineMods` 不能承担完整的递归合成适配职责。
