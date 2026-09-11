# 数据驱动机器定义模板：以 Farmer's Delight 为例

> 状态说明：本文描述的是拟实现的数据驱动机器格式。当前版本的 RS Integration **尚未提供该 JSON/KubeJS 加载器**，下面的定义不能直接放入数据包运行。当前 Farmer's Delight 仍由 `FarmersDelightRSModule`、`FarmersDelightRecipeHandler` 和对应 Java delegate 执行。

## 1. 最小标准机器模板

适用于固定物品输入槽、固定输出槽、自动启动且输出确定的单方块机器。

建议文件位置：

```text
data/<你的命名空间>/rs_integration/machines/<机器名>.json
```

可复制模板：

```json
{
  "formatVersion": 1,
  "id": "examplemod:machine_name",
  "requiredMod": "examplemod",
  "enabled": true,

  "binding": {
    "blocks": ["examplemod:machine_name"],
    "remoteGui": true,
    "displayItem": "examplemod:machine_name"
  },

  "recipes": {
    "types": ["examplemod:recipe_type"],
    "jeiCategories": ["examplemod:recipe_type"],
    "deterministicOutput": true,
    "runtimeDependentNbt": false
  },

  "inventory": {
    "capabilitySide": "internal",
    "inputSlots": [0],
    "outputSlots": [1],
    "containerSlots": [],
    "catalystSlots": [],
    "fuelSlots": [],
    "clearBeforeStart": true
  },

  "execution": {
    "start": {
      "mode": "insert"
    },
    "completion": {
      "mode": "expected_output",
      "slots": [1]
    },
    "timeoutTicks": 1200,
    "forceLoadWhileRunning": true,
    "parallelSafe": false
  },

  "recovery": {
    "returnExistingContents": true,
    "returnInputsOnFailure": true,
    "collectAllOutputSlots": true
  }
}
```

模板中的关键约束：

- `blocks` 必须使用完整的 `modid:block_id`，不要只写模组 ID。
- `types` 匹配服务端 `RecipeType`，不是配方文件自身的 ID。
- `jeiCategories` 只决定 JEI 配方分类如何路由到这台机器。
- `start.mode=insert` 表示放入材料后机器会自行开始。
- `expected_output` 必须比较物品、数量和所需 NBT，不能把输出槽中原有物品误判为本次产物。
- `parallelSafe` 默认应为 `false`，只有经过并发和机器占用测试后才能开启。

## 2. Farmer's Delight 烹饪锅实例

当前 Java 实现中的真实契约是：

| 项目 | Farmer's Delight 烹饪锅 |
|---|---|
| 方块类 | `vectorwing.farmersdelight.common.block.CookingPotBlock` |
| 配方类 | `vectorwing.farmersdelight.common.crafting.CookingPotRecipe` |
| 配方类型/JEI 分类 | `farmersdelight:cooking` |
| 普通输入槽 | `0..5` |
| 菜品展示槽 | `6` |
| 容器槽 | `7` |
| 标准输出槽 | `8` |
| 启动方式 | 放入材料后自动加工 |
| 额外条件 | 下方必须有有效热源 |
| 输出容器 | 碗等容器是实际输入，必须参与递归材料规划 |

若数据驱动层已经实现，对应定义可以写成：

```json
{
  "formatVersion": 1,
  "id": "farmersdelight:cooking_pot",
  "requiredMod": "farmersdelight",
  "enabled": true,

  "binding": {
    "blocks": ["farmersdelight:cooking_pot"],
    "remoteGui": true,
    "displayItem": "farmersdelight:cooking_pot"
  },

  "recipes": {
    "types": ["farmersdelight:cooking"],
    "jeiCategories": ["farmersdelight:cooking"],
    "deterministicOutput": true,
    "runtimeDependentNbt": false,
    "outputContainer": {
      "source": "recipe",
      "accessor": "getOutputContainer",
      "role": "consumed"
    }
  },

  "inventory": {
    "capabilitySide": "internal",
    "minimumSlots": 9,
    "inputSlots": [0, 1, 2, 3, 4, 5],
    "displaySlots": [6],
    "containerSlots": [7],
    "outputSlots": [8],
    "catalystSlots": [],
    "fuelSlots": [],
    "clearBeforeStart": true
  },

  "requirements": [
    {
      "type": "heated",
      "target": "below"
    }
  ],

  "execution": {
    "start": {
      "mode": "insert"
    },
    "completion": {
      "mode": "first_matching_output",
      "slots": [8, 6],
      "requireContainerConsumptionForFallback": true
    },
    "timeoutTicks": 2400,
    "forceLoadWhileRunning": true,
    "parallelSafe": true,
    "concurrencyScope": "machine_slot"
  },

  "recovery": {
    "returnExistingContents": true,
    "returnInputsOnFailure": true,
    "returnContainerOnFailure": true,
    "collectAllOutputSlots": false
  }
}
```

### 为什么这个实例不是最小模板

烹饪锅比普通一进一出机器多出三个语义：

1. 必须验证热源，否则已经扣除的材料需要退款。
2. 配方声明的碗等容器是每次操作的真实材料，必须进入递归图。
3. 某些兼容配方可能在展示槽 `6` 形成菜品，而不是直接进入输出槽 `8`，因此完成判定需要回退路径。

如果第一版数据驱动系统只支持标准输入/输出槽，建议暂时保留烹饪锅的专用 Java delegate，不要为了覆盖它而过早扩大 JSON 解释器的能力。

## 3. 对应的 KubeJS 机器定义

KubeJS API 应与 JSON 映射到同一个 `DataDrivenMachineDefinition`，避免形成两套行为。

```javascript
RSIntegrationEvents.machines(event => {
  event.register({
    formatVersion: 1,
    id: 'farmersdelight:cooking_pot',
    requiredMod: 'farmersdelight',

    binding: {
      blocks: ['farmersdelight:cooking_pot'],
      remoteGui: true,
      displayItem: 'farmersdelight:cooking_pot'
    },

    recipes: {
      types: ['farmersdelight:cooking'],
      jeiCategories: ['farmersdelight:cooking'],
      deterministicOutput: true,
      runtimeDependentNbt: false,
      outputContainer: {
        source: 'recipe',
        accessor: 'getOutputContainer',
        role: 'consumed'
      }
    },

    inventory: {
      capabilitySide: 'internal',
      minimumSlots: 9,
      inputSlots: [0, 1, 2, 3, 4, 5],
      displaySlots: [6],
      containerSlots: [7],
      outputSlots: [8],
      clearBeforeStart: true
    },

    requirements: [
      { type: 'heated', target: 'below' }
    ],

    execution: {
      start: { mode: 'insert' },
      completion: {
        mode: 'first_matching_output',
        slots: [8, 6],
        requireContainerConsumptionForFallback: true
      },
      timeoutTicks: 2400,
      forceLoadWhileRunning: true,
      parallelSafe: true,
      concurrencyScope: 'machine_slot'
    }
  })
})
```

脚本注册只声明机器契约。实际菜谱仍使用 Farmer's Delight 原生配方格式，例如：

```javascript
ServerEvents.recipes(event => {
  event.custom({
    type: 'farmersdelight:cooking',
    ingredients: [
      { item: 'minecraft:carrot' },
      { item: 'minecraft:potato' }
    ],
    result: {
      item: 'examplepack:vegetable_stew',
      count: 1
    },
    container: {
      item: 'minecraft:bowl'
    },
    cookingtime: 200,
    experience: 1.0
  }).id('examplepack:vegetable_stew')
})
```

这里的配方字段必须以目标模组实际支持的 JSON 格式为准，RSI 不应重新定义 Farmer's Delight 的菜谱格式。

## 4. 三种 Delight 机器的映射结论

| 机器 | 现有实现 | 纯数据驱动可行性 | 原因 |
|---|---|---|---|
| Cooking Pot | `CookingPotBatchDelegate` | 中等 | 固定槽位，但有热源、容器和展示槽回退 |
| Skillet | `SkilletBatchDelegate` | 低到中等 | 需要调用 `addItemToCook`，完成物可能作为世界物品弹出 |
| Cutting Board | `CuttingBoardBatchDelegate` | 低 | 概率输出、时运影响、工具耐久和工具返还 |

### 煎锅应如何定义

煎锅可以保留数据定义，但必须指定 Java 启动适配器：

```json
{
  "id": "farmersdelight:skillet",
  "requiredMod": "farmersdelight",
  "binding": {
    "blocks": ["farmersdelight:skillet"],
    "remoteGui": false
  },
  "recipes": {
    "types": ["minecraft:campfire_cooking"],
    "jeiCategories": ["minecraft:campfire"]
  },
  "execution": {
    "start": {
      "mode": "adapter",
      "adapter": "rs_integration:farmersdelight_skillet"
    },
    "completion": {
      "mode": "adapter",
      "adapter": "rs_integration:farmersdelight_skillet"
    }
  }
}
```

数据负责分类和绑定，适配器负责调用模组 API、识别烹饪结束以及捕获弹出的世界物品。

### 切菜板为什么应保留 Java

当前切菜板实现需要：

- 从配方读取工具 `Ingredient`；
- 把工具视为可复用催化剂，而不是普通消耗品；
- 验证剩余耐久能否覆盖批次数；
- 每次操作应用一次耐久损耗和耐久附魔概率；
- 按工具的时运等级生成概率产物；
- 将未损坏完的同一个 NBT 工具返还到原存储来源；
- 使用实际随机结果，而不是静态配方结果；
- 强制串行/flat execution。

这些语义如果全部开放成 JSON，会让解释器变成不安全的脚本虚拟机。建议仍注册专用 `ModType` 和 `CuttingBoardBatchDelegate`。

## 5. 全新模组机器的填写清单

复制最小模板前，先从目标模组确认下列信息：

```text
模组 ID：
机器方块 ID：
BlockEntity 类型或类名：
RecipeType ID：
JEI Category ID：
输入槽：
输出槽：
容器槽：
催化剂/工具槽：
燃料槽：
是否放入即启动：
完成状态如何判断：
是否需要热源/能源/流体：
输出是否固定：
输出是否继承输入 NBT：
是否有随机副产物：
失败时哪些槽位需要退款：
是否能同时使用多台机器：
是否允许远程 GUI：
```

判定规则：

- 全部是固定物品槽和确定性输出：使用纯 JSON/KubeJS 定义。
- 只有启动或完成判定特殊：数据定义加一个受控 Java adapter。
- 涉及随机、动态 NBT、多方块或非物品资源：使用专用 Java handler/delegate。

## 6. 加载时必须执行的校验

通用加载器至少应拒绝以下定义：

- 目标方块或配方类型注册名不存在；
- 输入、输出、容器和催化剂槽相互冲突；
- 槽位超出声明的 `minimumSlots`；
- 没有输入槽或输出完成策略；
- 声明确定性输出，但 handler 无法读取结果；
- 使用未知的 `start`、`completion` 或 adapter ID；
- `timeoutTicks` 非正数或超过服务端上限；
- 未审核便声明 `parallelSafe=true`；
- `remoteGui=true`，但目标没有可用 `MenuProvider` 或 GUI opener；
- 把概率配方声明为可进入纯递归图。

加载失败时应记录定义文件、JSON 路径和具体字段错误，并且整条机器定义失效，不能带着部分默认值继续执行。

## 7. 与当前 Farmer's Delight Java 实现的对应关系

| 数据驱动职责 | 当前 Java 位置 |
|---|---|
| `ModType`、JEI 路由 | `FarmersDelightRSModule.registerModType()` |
| 方块绑定与远程 GUI | `FarmersDelightRSModule.registerBindingTargets()` |
| 输入、输出、容器、概率语义 | `FarmersDelightRecipeHandler` |
| 烹饪锅槽位、热源、完成和回收 | `CookingPotBatchDelegate` |
| 煎锅/营火启动与世界产物捕获 | `SkilletBatchDelegate` |
| 切菜板随机结果与工具耐久 | `CuttingBoardBatchDelegate` |

数据驱动层的目标不是删除所有这些能力，而是把其中可证明为固定、确定且通用的部分提取到共享执行器中。
