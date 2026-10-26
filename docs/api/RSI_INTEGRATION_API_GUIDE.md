# RSI Integration API 接入指南

## 1. 接入方式

RSI Integration API 是代码 SPI，不是 RSI 专用 JSON 配方格式。

第三方模组仍然使用自己的：

- `RecipeType`
- `RecipeSerializer`
- JSON / datapack / codec
- `BlockEntity`
- capability / `Container` / `IItemHandler` / `IFluidHandler`

第三方只需要在 RSI 注册事件中，把这些内容投影为公共 API DTO。

```java
MinecraftForge.EVENT_BUS.addListener(
        (RSIIntegrationRegistrationEvent event) -> {
            event.api().registerRecipe(recipeIntegration);
            event.api().registerMachine(machineIntegration);
            event.api().registerExecution(executionIntegration);
        }
);
```

注册阶段只发生一次。注册冻结后，新增声明需要重启服务端。

## 2. RecipeIntegration

`RecipeIntegration.inspect` 必须返回不可变 `RecipeView`，不能把 live
`Ingredient`、`Recipe`、capability 或世界对象放进公共 DTO。

必须明确：

- `recipeId`
- `processId`
- item/fluid alternatives
- identity matching mode
- consumed/fuel/catalyst/transformed/container/durability 语义
- `inputGroup` 和可选 `inputSlot`
- 每个输出端口、范围、概率、动态身份和 target-producing 语义

如果配方只能用不可枚举 predicate 描述，不能注册为标准递归材料。应改用
`RecipeExecutionKind.NONE` 仅展示，或把不可递归部分放在 custom execution 内管理。

## 3. MachineIntegration

标准 I/O 机器返回显式 `MachineDescriptor`：

- item/fluid/energy ports
- slot/tank/side/access
- capacity
- accepted processes
- execution mode
- concurrency/cleanup/support scope
- descriptorRevision

`TICKED_AND_BUSY` 还必须提供 `MachineStateProvider`。状态提供器只能读状态，
不能投料、抽取、清理或缓存世界对象。

机器布局或语义改变时必须提升 `descriptorRevision`；同 revision 内容变化会因
fingerprint 不一致而被拒绝。

## 4. 标准 I/O 与 Custom Execution

优先使用 `RecipeExecutionKind.STANDARD_IO`：

- RSI 负责预留、提取、投料、输出容量模拟、真实收取和清理。
- 输入和输出必须能由显式机器端口表达。

以下情况使用 `RecipeExecutionKind.CUSTOM`：

- GUI
- 仪式
- 多方块内部流程
- 机器私有气体/化学品/温度/应力
- 无法安全映射到标准端口的执行

Custom execution 必须实现完整生命周期：

```text
createPlan
start
observe
collect
onFailure / onCancel
onSettled
snapshot / resume
```

`ExecutionPlan` 必须使用真实预留和真实端口：

- operationId 与请求一致
- recipe/machine/integration ID 一致
- item/fluid route 不超过 ReservedMaterial
- output port 集合与 RecipeView 完全一致
- descriptor/recipe fingerprint 与当前上下文一致

## 5. collect 语义

`collect` 可以多次调用：

- `collectionSequence` 必须单调递增
- 每次只报告上次之后的新真实产物
- `complete=true` 后才能结束结算
- item/fluid output identity、数量上限和保证下限都会被重新校验
- ReservedMaterial 必须按 entryId 完整处置

`MaterialDisposition`：

- `RECOVERED`：已真实回收到可退款位置
- `CONSUMED`：已确认不能退款
- `UNCERTAIN`：状态未知，不能乐观退款

`cleanupComplete=false` 或存在 `UNCERTAIN` 时，RSI 会拒绝自动退款并保留持久审计记录。

概率输出的物品与流体语义一致：

- 声明只描述一次成功触发后的资源和数量范围，不承诺规划期库存。
- 进度只按真实观测到的 ItemStack 或 `CollectedOutput` 数量增加。
- 流体必须保持 `FLUID + fluidId + NBT` 身份，不能把内部存储凭证当成普通物品交付。
- 已观测产出与已安全入库是两个阶段；前者可推进概率尝试，后者才计入订单已交付结果。

## 6. snapshot / resume

`OperationSnapshot` 只能包含 primitive、NBT 和稳定 metadata：

- 不保存 `Level`
- 不保存 `ServerPlayer`
- 不保存 capability / handler
- 不保存 Java callback
- 不保存旧 `RunningOperation`

RSI 会把 snapshot 写入 `rs_integration_external_operations` SavedData。

服务端重启后：

1. 读取 transactionId/operationId/attempt。
2. 重新查询 recipe、machine、execution claim。
3. 重新生成 descriptor 和 `RecipeView`。
4. 校验 revision/fingerprint。
5. pre-start 记录重新调用 `start`。
6. 已有 snapshot 的记录调用 `resume`。
7. 输出写入持久领取队列。

无法重新解析或状态不一致时，记录进入 `UNCERTAIN`，不会自动重放或伪造成功。

## 7. 注册冲突

同类型同 ID 注册会失败。多个 handler 同时匹配时：

- priority 最高者胜出
- 最高 priority 并列视为 ambiguous
- ambiguous claim 不会静默选一个

建议第三方 ID 使用自己的 mod namespace，并在发布后保持稳定。

## 8. 最小示例

可编译示例见：

```text
src/examplemod/java/com/example/rsiintegration/examplemod/RSIIntegrationExampleMod.java
```

该源集只引用公共 API、Minecraft 和 Forge 类型，并通过 `check` 持续编译。

## 9. Consumer API

只查询或下单、不注册配方和机器时，使用：

```text
com.huanghuang.rsintegration.api.consumer.*
```

入口为 `RSICraftConsumerApi.get()`。主流程是：

```text
findRecipes
  -> openInspection
  -> nextInspectionPage
  -> submit / submitBatch
  -> status / cancel
```

完整接入代码见：

```text
docs/reports/RSI_CONSUMER_API_INTEGRATION_GUIDE.md
```

Consumer 不读取 `RecipeIndex`、计划图、材料表、网络包或 delegate。网络传输必须使用
`ConsumerApiCodecs`，不能自行手写 DTO 字段顺序。

## 10. 版本与发布检查

公共 API 1.x 不允许向已发布 record 追加组件。需要新信息时新增独立可选类型或默认
方法；破坏性变化进入下一个 major。

API 收尾验证：

```text
gradlew test --tests "com.huanghuang.rsintegration.api.*"
gradlew test --tests "com.huanghuang.rsintegration.crafting.external.*"
gradlew compileExamplemodJava
gradlew verifyReleaseJar
```

`PublicApiBoundaryTest` 会检查公共 API 类型没有引用内部实现包；`verifyReleaseJar`
会检查 Integration 与 Consumer 的核心入口类已进入发布 jar。
