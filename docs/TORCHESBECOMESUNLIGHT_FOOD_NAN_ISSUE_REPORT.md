# Gathering Torches Become Sunlight：烤制食物可能写入 NaN 营养值

## 提 Issue 可用摘要

**标题建议：** 烤炉制作大列巴时，成品的 `dish_attribute.nutrition` 可能为 `NaN`，导致依赖稳定物品 NBT 身份的存储/合成系统无法读取该物品

**环境：** Minecraft 1.20.1、Forge 47.x、`torchesbecomesunlight-0.5.0-hotfix2.jar`。该文件的 SHA-256 为 `B2D6B4DA7F7F81824CE13ACE1D0C3CD2D03EF5EDF0284A039BCF3112D5D82B80`。JAR 内 `META-INF/mods.toml` 仍声明版本 `1.20.1-0.4.2`；请按文件哈希核对实际发行包。

**玩家观察：** 制作并食用大列巴（Ursus Bread）或其他食物后，RS Integration 的递归合成配方树仍能打开，但执行任意物品合成时会显示大量不相关的“缺少材料”，包括“利爪/悬熊爪 ×2”“生牛肉/生猪排/生鸡肉/生兔肉（+44 更多）×4”“科莫多巨蜥唾液 ×3”等。此前相同操作可用。尚未取得出问题存档的 `latest.log` 与实物 NBT，因此“玩家存档中确有 NaN”还需现场核验。

**期望：** 食物 NBT 中的营养数值始终为有限值。制作或食用食物不应影响其他物品的存储与递归合成。

## JAR 内可直接验证的原因

1. `data/torchesbecomesunlight/recipes/oven/ursus_bread.json` 的配方只要求 `torchesbecomesunlight:dough`。
2. JAR 自带的 `data/torchesbecomesunlight/recipes/food_values/default_set/*.json` 没有为 `torchesbecomesunlight:dough` 定义营养类别。`eg1.json` 虽然列出 `torchesbecomesunlight:ursus_bread`，但烤炉计算成品属性时查询的是**输入槽中的材料**，不是成品。
3. `com.freefish.torchesbecomesunlight.server.block.blockentity.OvenBlockEntity.decorateFinalFood()` 汇总输入槽的 `FoodValues`，新建 `DishAttribute`，调用 `resultNutrition(RandomSource)`，再通过 `TBSFood.setItemAdditionData(...)` 写入输出食物。
4. `com.freefish.torchesbecomesunlight.server.item.food.DishAttribute.resultNutrition(...)` 将 `VEGGIE`、`MEAT`、`FISH`、`EGG` 的最大值作为分母。如果四项都是 `0`，该方法继续计算 `0.0f / 0.0f`，结果为 `Float.NaN`，没有零分母保护。
5. `DishAttribute.serializeNBT()` 将该值写到 `nutrition`；`TBSFood.getSaveDataName()` 返回 `dish_attribute`；JAR 内附带的 RosmontisLib `ItemAdditionData.setItemAdditionData(...)` 将序列化结果写入物品根 NBT。因此目标路径是 `tag.dish_attribute.nutrition`。

问题计算可简化为：

```java
float maximum = Math.max(0, Math.max(veggie, Math.max(meat, Math.max(fish, egg))));
integratedNutrition = (veggie / maximum + meat / maximum
        + fish / maximum + egg / maximum) / 2;
```

按这份 JAR 的默认数据，未添加其他提供营养值材料的大列巴存在稳定产生 `NaN` 的代码路径。其他输入营养总量为零的烤炉、炖锅或砧板食物也可能受到同一方法影响。`TBSFood.finishUsingItem(...)` 只调用父类方法；从这份字节码看，异常值是在**制作食物**时产生，食用是玩家发现问题的时间点，并非已证实的写入时机。

## 建议的最小复现与核验

1. 使用上述 JAR 的默认数据，在烤炉中仅按 `oven/ursus_bread.json` 用面团制作大列巴，不放额外配料。
2. 取出成品，检查物品 NBT 的 `dish_attribute.nutrition` 是否为 `NaN`。可先将食物放在玩家背包，再用游戏内 `/data get entity @p Inventory[{id:"torchesbecomesunlight:ursus_bread"}].tag.dish_attribute` 查看；也可用 NBT 查看工具。
3. 将该食物放入 RS/BD 存储网络，验证其他正常材料仍在网络中；随后用 RSI 请求一个原本可合成的简单配方，记录服务端 `latest.log` 和缺料提示。
4. 从存储网络移走带异常 NBT 的食物后重试，比较结果。若仍失败，请附存档和日志，排查是否还有其他带 `NaN` 的物品。

第 1～2 步用于单独验证上游食物问题；第 3～4 步用于确认玩家遇到的跨模组症状是否由同一份异常 NBT 引起。

## 建议的上游修复

在 `DishAttribute.resultNutrition(...)` 中，先检查四类营养的最大值。当最大值为 `0` 或非有限数时，将 `integratedNutrition` 设为明确的有限默认值（例如 `0`），跳过除法；写入 NBT 前再检查 `Float.isFinite(...)`。同时为面团等实际烹饪输入补足营养定义，并分别测试“无营养材料”“仅一种营养类别”“混合类别”三种配方。

## RSI 侧兼容处理

RS Integration 现在会在 RS 存储快照中单独跳过身份无法稳定表示的物品，并保留同一网络里的其他正常材料。BD 快照原本已有同类处理。此兼容仅保护递归合成读取；它不会修改玩家食物的 NBT，也不能让带 `NaN` 的食物本身参与需要精确身份的合成。长期修复仍应在食物模组生成成品时避免写入非有限数值。
