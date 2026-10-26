# RSI Integration API 接入指南

本文面向准备接入 RSI 的第三方模组开发者。阅读完应能完成：

- 将已有 `Recipe` 投影为 RSI 可递归规划的材料和产物。
- 描述机器显式输入输出端口与执行模式。
- 实现标准 I/O 或自定义多 tick 执行器。
- 通过 Consumer API 查询配方、批量检查、下单、查询状态和取消。
- 使用公共合同测试和 `rsi_api_smoke` 验证真实模组场景。

公共 API 是 Java SPI，不是 RSI 专用 JSON 配方格式。第三方模组继续使用自己的
`RecipeType`、`RecipeSerializer`、datapack、`BlockEntity` 和 capability。

## 0. 先看边界

RSI 负责：

- 递归配方图、材料数量、输入组和候选路由。
- 库存快照、材料预留、DAG 调度、机器租约和事务回滚。
- 标准 I/O 投料、输出基线、真实收集和终态结算。
- Consumer inspection、plan、幂等订单、状态和取消。

第三方负责：

- 声明真实配方内容，不伪造配方中不存在的材料或产物。
- 声明机器端口、执行模式和可选状态提供器。
- 对特殊机器实现真实 `start/observe/collect/cleanup`。
- 保证公共 ID、revision 和 fingerprint 稳定。

以下内容不能从第三方接入：

- 直接修改 RSI 内部计划图、材料账本或机器租约。
- 从公共 API 暴露 live `ServerPlayer`、`Level`、`BlockEntity` 或 capability 实例。
- 把气体、化学品、温度、应力等私有资源伪装成普通物品或流体。

字段不可变性、防御性复制、版本规则和线程边界以本文及对应公共类型的 Javadoc
为准。第三方接入不需要读取 RSI 内部实现或未公开设计稿。

## 1. 接入方式

第三方只需要在 Forge 总线上监听注册事件：

```java
MinecraftForge.EVENT_BUS.addListener(
        (RSIIntegrationRegistrationEvent event) -> {
            event.api().registerRecipe(
                    new MyRecipeIntegration(),
                    new RegistrationOptions(100, Map.of())
            );
            event.api().registerMachine(
                    new MyMachineIntegration(),
                    new RegistrationOptions(100, Map.of())
            );
            event.api().registerExecution(
                    new MyExecutionIntegration(),
                    new RegistrationOptions(100, Map.of())
            );
        }
);
```

注册只发生在服务端启动阶段。注册冻结后新增声明需要重启服务端。

### 1.1 依赖边界

第三方只需要把 RSI jar 作为 `compileOnly` 依赖，并声明 `mods.toml` 依赖。
不要在 RSI 缺失时加载直接引用 API 的类。

推荐的延迟加载方式：

```java
if (ModList.get().isLoaded("rs_integration")) {
    MyRsiIntegrationBootstrap.initialize();
}
```

`MyRsiIntegrationBootstrap` 才直接 import `com.huanghuang.rsintegration.api.*`。

### 1.2 线程规则

| 回调 | 线程 |
|---|---|
| `supports` 类型判断 | 配方索引或机器扫描，禁止访问世界 |
| `inspect` | 默认服务端主线程 |
| `RecipeAvailabilityProvider` | 服务端主线程 |
| `MachineIntegration.describe` | 服务端主线程 |
| `MachineStateProvider.readState` | 服务端主线程，只读 |
| `createPlan/start/observe/collect/cleanup` | 服务端主线程 |
| Consumer 业务调用 | 拥有玩家的服务端主线程 |

不要在异步线程直接读取库存、BlockEntity、capability、机器 GUI 或发送网络包。

### 1.3 稳定身份

- 所有 integration ID 使用自己的 mod namespace。
- `recipeId` 必须是被检查的真实 live recipe ID。
- `processId` 是配方与机器之间唯一路由键。
- 同一 `inputGroup` 和 `outputGroup` 在一次操作内稳定。
- 配方或机器语义变化时提升 revision，并保持 fingerprint 可重新计算。
- 已发布 ID 不要复用给另一种语义。

## 2. 从现有模组开始

第三方已有内容通常分三类：

| 现有内容 | 推荐方式 |
|---|---|
| 普通工作台配方 | `RecipeExecutionKind.STANDARD_IO` 或内部纯配方路径 |
| 固定槽位、固定 tank 的机器 | `MachineDescriptor + STANDARD_IO` |
| GUI、仪式、多方块、私有资源 | `RecipeExecutionKind.CUSTOM` |

不要把特殊机器硬塞进标准 I/O。标准 I/O 无法证明输入输出隔离时，应改为 custom
execution，而不是增加 fallback 字段。

## 3. RecipeIntegration

一个配方 integration 只负责把 live recipe 投影成不可变 `RecipeView`：

```java
public final class MyRecipeIntegration
        implements RecipeIntegration<MyRecipe> {
    @Override
    public ResourceLocation id() {
        return new ResourceLocation("mymod", "recipe");
    }

    @Override
    public boolean supports(Recipe<?> recipe) {
        return recipe instanceof MyRecipe;
    }

    @Override
    public RecipeExecutionKind executionKind() {
        return RecipeExecutionKind.STANDARD_IO;
    }

    @Override
    public RecipeInspectionResult inspect(
            RecipeContext context,
            MyRecipe recipe
    ) {
        // 从 recipe 读取真实 Ingredient、数量、副产物、概率和 NBT。
        // 返回 new RecipeView(...) 或带稳定诊断的 rejected(...).
    }
}
```

必须明确：

- `recipeId`
- `processId`
- item/fluid alternatives
- identity matching mode
- consumed/fuel/catalyst/transformed/container/durability 语义
- `inputGroup` 和可选 `inputSlot`
- 每个输出端口、范围、概率、动态身份和 target-producing 语义

如果 recipe 只能通过不可枚举 predicate 描述，不能注册为标准递归材料。应改用
`RecipeExecutionKind.NONE` 仅展示，或把不可递归部分放在 custom execution 内管理。

## 4. MachineIntegration

标准 I/O 机器返回显式 `MachineDescriptor`：

```java
public final class MyMachineIntegration
        implements MachineIntegration {
    @Override
    public ResourceLocation id() {
        return new ResourceLocation("mymod", "machine");
    }

    @Override
    public boolean supports(BlockEntity blockEntity) {
        return blockEntity instanceof MyMachineBlockEntity;
    }

    @Override
    public MachineDescriptorResult describe(
            MachineContext context,
            BlockEntity blockEntity
    ) {
        // 返回显式 item/fluid/energy ports。
        // 正常不可用时返回 rejected(diagnostic)。
    }
}
```

`MachineDescriptor` 必须包含：

- accepted `processId`
- 每个 item/fluid port 的稳定 `portId`
- slot/tank、方向、INPUT/OUTPUT、access
- `inputGroup`/`outputGroup`
- capacity
- execution mode
- batching、concurrency、cleanup、support scope
- `descriptorRevision`

机器结构、端口或执行语义改变时必须提升 revision。仅改 revision 名称而不改内容会因
fingerprint 校验失败。

`TICKED_AND_BUSY` 还必须提供 `MachineStateProvider`。状态提供器只能读状态，不能投料、
抽取、清理或缓存世界对象。

## 5. 标准 I/O

优先使用 `RecipeExecutionKind.STANDARD_IO`。标准执行器会：

1. 校验材料和机器 descriptor revision。
2. 模拟输出容量。
3. 从存储提取材料。
4. 按 `inputGroup` 写入显式机器端口。
5. 等待机器完成或输出增量。
6. 只收集配方声明且实际出现的产物。
7. 处理容器返还、失败回滚和订单结算。

标准 I/O 不支持：

- GUI 内拖拽或仪式步骤。
- 无法显式映射的私有资源。
- 同资源输入输出且无法通过槽位和输出基线隔离。
- 实体产物和无法证明收取的世界掉落。

这些情况必须使用 custom execution。

## 6. Custom Execution

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

`createPlan` 只能使用真实预留和显式 routes：

```java
ResourceLocation machineId = descriptor.machineId();
long recipeRevision = context.recipeContext().revision();
long descriptorRevision = descriptor.descriptorRevision();
String descriptorFingerprint = descriptor.descriptorFingerprint();

ExecutionPlan plan = new ExecutionPlan(
        context.operationId(),
        context.recipe().recipeId(),
        machineId,
        context.recipeContext().integrationId(),
        context.machine().integrationId(),
        id(),
        recipeRevision,
        descriptorRevision,
        context.recipe().definitionFingerprint(),
        descriptorFingerprint,
        itemRoutes,
        fluidRoutes,
        List.of(),
        expectedTicks,
        outputPorts,
        supportScope,
        concurrency
);
```

实际实现应从当前 claim 的 `RecipeView` 和 `MachineDescriptor` 生成上述字段，不能
复制本文占位值。重点是：

- operationId 与请求一致
- recipe/machine/integration ID 一致
- route 不超过 `ReservedMaterial`
- output port 集合与 `RecipeView` 完全一致
- descriptor/recipe fingerprint 与当前上下文一致

`collect` 可以多次调用：

- `collectionSequence` 必须单调递增
- 每次只报告上次之后的新真实产物
- `complete=true` 后才能结束结算
- item/fluid identity、数量上限和保证下限会重新校验
- 所有 ReservedMaterial 必须按 entryId 完整处置

`MaterialDisposition`：

- `RECOVERED`：已真实回收到可退款位置
- `CONSUMED`：已确认不能退款
- `UNCERTAIN`：状态未知，不能乐观退款

`cleanupComplete=false` 或存在 `UNCERTAIN` 时，RSI 不会自动退款。

`OperationSnapshot` 只能保存 primitive、NBT 和稳定 metadata：

- 不保存 `Level`
- 不保存 `ServerPlayer`
- 不保存 capability / handler
- 不保存 Java callback
- 不保存旧 `RunningOperation`

服务端重启后，RSI 会重新解析 recipe/machine/execution claim，并用 `resume` 恢复活动
阶段。无法复核的操作进入 uncertain 清理，不会伪造成功。

## 7. Consumer API

只查询或下单、不注册配方和机器时，使用 Consumer API：

```text
com.huanghuang.rsintegration.api.consumer.*
```

标准流程：

```text
findRecipes
  -> openInspection
  -> nextInspectionPage
  -> submit / submitBatch
  -> status / cancel
```

最小查询示例：

```java
RSICraftConsumerApi api = RSICraftConsumerApi.get().orElse(null);
if (api == null || api.state() != ConsumerServiceState.READY) {
    return;
}

ConsumerResult<ConsumerRecipeCatalogPage> found = api.findRecipes(
        player,
        new ConsumerRecipeCatalogRequest(
                new ConsumerRecipeQuery.ByOutput(
                        IntegrationResourceKey.item(result),
                        ResourceMatchMode.ANY_IDENTITY
                ),
                64,
                0L
        )
);
```

Inspection 会冻结一次库存、配方图、机器绑定和 storage snapshot。分页期间不能换
snapshot。只有 `READY` craftability 会携带 `ConsumerPlanId`。

下单幂等键是：

```text
playerId + consumerId + externalRequestId
```

网络超时重试必须复用同一 `ConsumerExternalRequestId`。已消费 plan 不能再次提交。

完整 Consumer 调用示例：

```java
ConsumerInspectionRequest request = new ConsumerInspectionRequest(
        new ResourceLocation("mymod", "consumer"),
        new ConsumerInspectionRequestId(UUID.randomUUID()),
        recipes,
        new ConsumerMachineSelector.AutomaticBound(),
        new ConsumerStorageSelector.Default(),
        ConsumerOutputDestination.PLAYER_INVENTORY,
        new ConsumerInspectionOptions(64, true)
);

ConsumerResult<ConsumerInspectionTicket> opened =
        api.openInspection(player, request);
if (!opened.successful()) {
    ConsumerDiagnostic diagnostic = opened.diagnostics().get(0);
    // 根据稳定 code/detail 处理，不解析异常字符串。
    return;
}

ConsumerInspectionTicketId ticketId = opened.value().ticketId();
ConsumerResult<ConsumerInspectionPage> pageResult =
        api.nextInspectionPage(player, ticketId);
if (!pageResult.successful()
        || pageResult.value().state()
        != ConsumerInspectionPageState.COMPLETE) {
    // RUNNING/PENDING 时稍后使用同一 ticket 再次查询。
    return;
}

for (ConsumerCraftability craftability : pageResult.value().results()) {
    if (!craftability.canSubmit()) continue;
    ConsumerPlanId planId = craftability.planId();
    ConsumerResult<ConsumerOrderReceipt> submitted = api.submit(
            player,
            new ConsumerOrderRequest(
                    new ResourceLocation("mymod", "consumer"),
                    new ConsumerExternalRequestId(requestId),
                    planId
            )
    );
}
```

可直接编译的 Consumer 示例：

```text
src/test/java/com/example/rsiintegration/PublicConsumerExample.java
```

Consumer 不读取 `RecipeIndex`、计划图、材料表、网络包或 delegate。网络传输必须使用
`ConsumerApiCodecs`，不能自行手写 DTO 字段顺序。

## 8. 注册冲突

同类型同 ID 注册会失败。多个 handler 同时匹配时：

- priority 最高者胜出
- 最高 priority 并列视为 ambiguous
- ambiguous claim 不会静默选一个

建议第三方 ID 使用自己的 mod namespace，并在发布后保持稳定。

## 9. 最小完整接入清单

发布前逐项确认：

- `mods.toml` 声明 RSI 依赖，缺失时延迟加载 API 类。
- 注册事件只触发一次。
- recipe/machine/execution ID 稳定且使用自己的 namespace。
- `supports` 无世界访问和无异常副作用。
- `inspect` 不返回 live Recipe、Ingredient 或 capability。
- 所有数量和概率输出均来自真实 recipe 语义。
- 标准 I/O 的 inputGroup 与 machine port 完全匹配。
- `TICKED_AND_BUSY` 提供只读状态提供器。
- custom operation 完整处理 start/observe/collect/failure/cancel/settled。
- 所有 reserved material 在完成或清理时有且仅有一次处置。
- 重复 operationId/requestId 不产生第二次物理操作。
- 配方或机器 reload 后旧 plan 返回稳定 stale 诊断。
- `PublicApiBoundaryTest` 不报内部 import。
- `rsi_api_smoke` 或等价真实模组场景通过。

## 10. 可运行示例与烟测

编译期公共示例：

```text
src/examplemod/java/com/example/rsiintegration/examplemod/RSIIntegrationExampleMod.java
src/test/java/com/example/rsiintegration/PublicIntegrationExample.java
src/test/java/com/example/rsiintegration/PublicConsumerExample.java
```

真实模组烟测：

```text
src/apismoke
docs/api/RSI_API_SMOKE_TEST_GUIDE.md
```

当前烟测覆盖：

- FarmersDelight 烹饪锅：真实 recipe 投影、容器输入、`OUTPUT_DELTA_ONLY`
- IronFurnaces：真实 BlockEntity descriptor、`TICKED_AND_BUSY`、进度和 revision

### 10.1 自动测试案例

| 案例 | 验证内容 | 命令 |
|---|---|---|
| Public API 边界 | 公共类型和烟测模组不 import 内部实现 | `gradlew test --tests "com.huanghuang.rsintegration.api.PublicApiBoundaryTest"` |
| Registration phase | CREATED/REGISTRATION/FROZEN/STOPPED 生命周期 | `gradlew test --tests "com.huanghuang.rsintegration.api.IntegrationRegistrationServiceTest"` |
| Claim 冲突 | priority、重复 ID、最高优先级并列 ambiguous | `gradlew test --tests "com.huanghuang.rsintegration.api.IntegrationClaimRegistryTest"` |
| RecipeView | 数量、概率、动态身份、NBT fingerprint、容器关联 | `gradlew test --tests "com.huanghuang.rsintegration.api.recipe.*"` |
| Machine descriptor | 端口、revision、fingerprint、状态不变量 | `gradlew test --tests "com.huanghuang.rsintegration.api.machine.*"` |
| Custom execution 合同 | route、collection sequence、cleanup、resume | `gradlew test --tests "com.huanghuang.rsintegration.api.execution.*"` |
| 外部执行适配 | 标准 I/O、custom fluid、幂等清理、真实输出 | `gradlew test --tests "com.huanghuang.rsintegration.crafting.external.*"` |
| Consumer DTO/Codec | 所有嵌套变体、未知 wire version、顺序保持 | `gradlew test --tests "com.huanghuang.rsintegration.api.consumer.*"` |
| Consumer runtime | plan、输出、真实产物和订单 journal | `gradlew test --tests "com.huanghuang.rsintegration.crafting.batch.ConsumerCraftingRuntimeTest"` |
| Consumer 678 配方 | 稳定排序、分页无重复遗漏 | `gradlew test --tests "com.huanghuang.rsintegration.crafting.external.ExternalConsumerCatalogServiceTest"` |
| FarmersDelight 真实投影 | `CookingPotRecipe -> RecipeView` | `gradlew test --tests "com.example.rsiintegration.apismoke.FarmersDelightCookingPotIntegrationTest"` |
| IronFurnaces 真实投影 | `TICKED_AND_BUSY` descriptor 和 recipe view | `gradlew test --tests "com.example.rsiintegration.apismoke.IronFurnacesSmeltingIntegrationTest"` |

### 10.2 游戏内 Integration 案例

启动：

```text
gradlew runClient
```

FarmersDelight 烹饪锅：

1. 放置并绑定烹饪锅，下方准备热源。
2. 使用 `farmersdelight:cooking/beef_stew`。
3. RS 中准备配方材料和空碗。
4. 预期计划包含食材与容器，完成后只在真实输出槽出现料理时结算。
5. 失败后输入槽和容器槽不能出现重复返还。

IronFurnaces：

1. 放置普通 Iron Furnace，不使用 generator/factory augment。
2. 燃料槽放入足够燃料。
3. 使用 `rsi_api_smoke:smelt_test_raw_iron`。
4. 预期机器在 `TICKED_AND_BUSY` 下报告进度，铁锭真实出现前不完成订单。

### 10.3 游戏内 Consumer 案例

```text
/rsi_api_smoke catalog minecraft:iron_ingot
/rsi_api_smoke inspect rsi_api_smoke:smelt_test_raw_iron
/rsi_api_smoke page <ticket-uuid>
/rsi_api_smoke submit <ticket-uuid>
/rsi_api_smoke status
/rsi_api_smoke cancel
```

预期：

- catalog 返回稳定 recipe revision 和分页 cursor。
- inspection 只创建一次 snapshot ticket。
- RUNNING 页面不返回半页结果。
- READY craftability 必须携带 plan。
- 同一 plan 第二次 submit 返回已消费诊断。
- status/cancel 只操作当前 player 和 consumer 的订单。

## 11. 版本与发布检查

公共 API 1.x 不允许向已发布 record 追加组件。需要新信息时新增独立可选类型或默认
方法；破坏性变化进入下一个 major。

API 收尾验证：

```text
gradlew test --tests "com.huanghuang.rsintegration.api.*"
gradlew test --tests "com.huanghuang.rsintegration.crafting.external.*"
gradlew test --tests "com.example.rsiintegration.apismoke.*"
gradlew compileExamplemodJava
gradlew apismokeClasses
gradlew verifyReleaseJar
```

`PublicApiBoundaryTest` 会检查公共 API 类型和烟测模组没有引用内部实现包；
`verifyReleaseJar` 会检查 Integration 与 Consumer 的核心入口类已进入发布 jar。

## 12. 常见错误

### 把机器私有资源伪装成物品

错误：用带特殊 NBT 的无意义物品代表气体、温度或应力。

正确：由 custom execution 读取并校验机器私有资源。RSI 只规划真实物品和流体。

### 在 supports 中读取机器

错误：`supports(BlockEntity)` 中检查机器进度或扫描附近方块。

正确：`supports` 只判断类型；状态和结构放到 `describe`/`stateProvider`。

### 每次调用生成新 ID

错误：每次 preview/retry 都生成新 `requestId` 或 `operationId`。

正确：同一逻辑请求和同一物理操作复用稳定 ID。

### 把显示名当身份

错误：使用本地化名称、hover name 或异常字符串做路由。

正确：使用 `ResourceLocation`、`IntegrationResourceKey`、规范化 NBT 和
稳定 diagnostic code。

### 在失败时直接补发模板产物

错误：机器没有实际输出，但使用 recipe 模板生成产物。

正确：只结算 `collect` 观察到的真实产物。不足时保持未完成或失败。

## 13. 排错顺序

1. 先检查 `state()` 和 registration phase。
2. 检查 integration ID、processId、priority 和 ambiguous claim。
3. 检查 `RecipeInspectionResult` 是否 rejected。
4. 检查 machine descriptor 的 accepted process、port group、slot/tank 和 revision。
5. 检查 `TICKED_AND_BUSY` 状态是否满足 busy/progress/activeRecipeId 约束。
6. 检查 custom `ExecutionPlan` 是否与 recipe view、reserved material 和 descriptor 一致。
7. 检查 collect sequence、output minimum、material disposition 和 cleanupComplete。
8. 检查 Consumer plan 是否过期、已消费或属于其他 player/consumer。

失败报告和日志中优先使用稳定 diagnostic code，不要解析异常文本。
