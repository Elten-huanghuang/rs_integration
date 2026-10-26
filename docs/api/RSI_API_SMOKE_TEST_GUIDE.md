# RSI API 真实烟测指南

## 1. 启动

真实烟测位于独立源集：

```text
src/apismoke
```

启动开发客户端：

```text
gradlew runClient
```

Mod 列表必须包含：

```text
RS Integration
RSI API Smoke Test
Farmer's Delight
Iron Furnaces
```

`rsi_api_smoke` 只允许引用：

- Minecraft / Forge
- 被测第三方模组
- `com.huanghuang.rsintegration.api.*`

`PublicApiBoundaryTest` 会阻止它引用 RSI 内部 `crafting`、delegate、网络包或
`mods.*` 类。

## 2. FarmersDelight 烹饪锅

测试目标：

- 真实 `CookingPotRecipe` 投影为公共 `RecipeView`
- 多输入槽
- 空碗等容器输入
- 输出槽基线
- `OUTPUT_DELTA_ONLY` 完成判定

建议配方：

```text
farmersdelight:cooking/beef_stew
```

准备：

- 已绑定 Farmer's Delight 烹饪锅
- 烹饪锅下方有热源
- RS 网络中有配方材料和一个空碗
- 输出槽开始前为空

成功判定：

- Consumer/JEI 计划显示食材和空碗需求
- 食材进入 0 到 5 槽
- 空碗进入容器槽
- 只有料理实际进入输出槽后订单才完成
- 收集后输入槽、容器槽和输出槽没有重复材料

## 3. Iron Furnaces

测试目标：

- `TICKED_AND_BUSY`
- busy、progress、totalProgress
- activeRecipeId/activeProcessId
- stateRevision 变化
- 输出槽基线

烟测配方：

```text
rsi_api_smoke:smelt_test_raw_iron
```

准备：

- 已绑定普通 Iron Furnace
- 不需要 factory/generator augment
- 燃料槽预先放入足够燃料
- RS 网络中有粗铁

成功判定：

- 库存检查返回 READY，而不是 MACHINE_UNAVAILABLE
- 粗铁进入输入槽
- 状态提供器能报告 cookTime/totalCookTime
- 熔炼完成前不收取输出
- 输出槽出现铁锭后才完成并交付

## 4. 自动合同测试

```text
gradlew test --tests "com.example.rsiintegration.apismoke.*"
gradlew test --tests "com.huanghuang.rsintegration.api.PublicApiBoundaryTest"
gradlew compileApismokeJava
```

自动测试验证真实配方投影、机器 descriptor 和公共 API 边界；真实机器行为仍必须在
`runClient` 中按上面的场景检查。

## 5. 下一批

- Embers Rekindled：物品+流体、概率/动态产物、custom execution。
- rsfoodorder：678 配方 Consumer inspection、plan、submitBatch、幂等和取消。
