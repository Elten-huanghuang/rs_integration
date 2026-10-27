# RSI Public API 接入指南

| 项目 | 值 |
|---|---|
| Minecraft | 1.20.1 |
| Java | 17 |
| Forge | 47.x |
| Integration API | 1.1.x |
| Resonance Inventory API | 1.0.x |
| Consumer API | 1.0.x |

本文面向第三方模组作者。目标是让作者在不读取 RSI 实现代码的前提下，完成配方投影、
机器绑定、特殊执行、JEI 接入、共振盘物品访问和 Consumer 下单。

RSI 不要求第三方改写现有配方 JSON。第三方保留自己的 `RecipeType`、
`RecipeSerializer`、datapack、`BlockEntity` 和 capability，通过本文定义的 Java
接口把真实语义告诉 RSI。

公共 API 的有限互操作授权已并入仓库根目录的唯一许可证 `LICENSE`，发布 jar 中
对应 `META-INF/LICENSE_RS_INTEGRATION`。该授权只覆盖本文列出的公共 API；RSI
主体、实现代码和原创素材仍适用同一文件中的专有条款。

## 实例索引

仓库中的 `src/apismoke` 是独立编译的 API 烟测模组，`src/test/java/com/example` 还包含
公共 API 边界示例。它们只引用公共 API，并用于验证第三方从外部模组接入时能否完成
注册、规划、JEI 展示、共振盘访问和执行。第一次接入时，先阅读
[ApiSmokeRegistrar.java](../../src/apismoke/java/com/example/rsiintegration/apismoke/ApiSmokeRegistrar.java)，
再按机器类型选择一个完整案例。

| 场景 | 参考实现 | 重点 |
|---|---|---|
| 普通配方向与固定容器槽位 | [FarmersDelightCookingPotIntegration.java](../../src/apismoke/java/com/example/rsiintegration/apismoke/FarmersDelightCookingPotIntegration.java) | `RecipeIntegration`、`MachineIntegration`、容器端口和材料顺序 |
| 带燃料、处理进度和一次批量投料的标准机器 | [IronFurnacesSmeltingIntegration.java](../../src/apismoke/java/com/example/rsiintegration/apismoke/IronFurnacesSmeltingIntegration.java) | `STANDARD_IO`、`TICKED_AND_BUSY`、状态 revision、`supportsBatching` |
| 可选的 Ars Nouveau 兼容 | [ArsNouveauIntegration.java](../../src/apismoke/java/com/example/rsiintegration/apismoke/ArsNouveauIntegration.java) | 可选模组隔离、有序材料、apparatus 与 imbuement 配方 |
| 概率流体和自定义执行 | [IronAlchemistCauldronIntegration.java](../../src/apismoke/java/com/example/rsiintegration/apismoke/IronAlchemistCauldronIntegration.java) | 自定义配方类型、`RecipeExecutionIntegration`、概率输出和流体结算 |
| RSI 本体内置 API 兼容 | [ProfoundCurseApiIntegration.java](../../src/main/java/com/huanghuang/rsintegration/mods/profoundcurse/ProfoundCurseApiIntegration.java) | 邻接多方块结构、8 个展示架、持久操作、失败回收和成品回写 |
| 共振盘作为额外物品来源 | [PublicResonanceExample.java](../../src/test/java/com/example/rsiintegration/PublicResonanceExample.java) | 快照、精确 NBT 匹配、模拟提取和实际提取 |
| Consumer API 全流程 | [ConsumerSmokeCommand.java](../../src/apismoke/java/com/example/rsiintegration/apismoke/ConsumerSmokeCommand.java) | catalog、inspection、batch、submit、status 和 cancel |

这些文件是验收示例，不是复制模板：

- `rsi_api_smoke` 的 namespace、测试配方和 `priority=1000` 只用于覆盖测试，第三方应使用
  自己的稳定 ID；普通兼容实现应优先使用 `RegistrationOptions.defaults()`。
- 示例会针对不同模组做取舍，不能把一个案例中的槽位、燃料或输出规则直接套到另一台机器。
- 示例源码属于独立 smoke mod，不在 RSI 主 jar 的公共 API 包中；可以依照其调用方式接入，
  但稳定契约仍是本文列出的 `com.huanghuang.rsintegration.api` 公共类型。

## 1. 选择正确的接入面

RSI 提供三套彼此独立的公共 API。

| 需求 | 使用 |
|---|---|
| 查询 RSI 已识别的配方、检查递归材料、提交或取消订单 | Consumer API |
| 把共振盘内容作为额外玩家物品来源 | Resonance Inventory API |
| 让 RSI 识别你的普通配方和固定槽位机器 | Integration API |
| 让 RSI 执行仪式、GUI 流程、多方块或机器私有资源 | Integration API + `RecipeExecutionIntegration` |

Integration API 负责把模组内容接入递归规划；Consumer API 负责从 RSI 外部发起业务
请求；Resonance Inventory API 负责让第三方物品或工具读取共振盘中与玩家关联的存储。
一个模组可以只使用其中一套，也可以同时使用。

稳定的 Java 类型只在以下公共包中声明：

```text
com.huanghuang.rsintegration.api
com.huanghuang.rsintegration.api.recipe
com.huanghuang.rsintegration.api.machine
com.huanghuang.rsintegration.api.execution
com.huanghuang.rsintegration.api.resonance
com.huanghuang.rsintegration.api.consumer
com.huanghuang.rsintegration.api.diagnostic
```

公共 API 之外的实现类、注册表、网络包和调试入口不属于兼容契约。

## 2. 添加依赖并注册

第三方需要以 `compileOnly` 方式编译 RSI，并在运行时声明 RSI 为必需依赖。不要在
RSI 缺失时加载直接引用 API 的类；如果 RSI 是可选依赖，应把全部接入代码隔离到单独
的 bootstrap 类，只在确认 `rs_integration` 已加载后初始化该类。

`mods.toml` 示例：

```toml
[[dependencies.mymod]]
modId = "rs_integration"
mandatory = true
versionRange = "[1.5.2,2.0.0)"
ordering = "AFTER"
side = "BOTH"
```

第三方应在 Mod 总线的 `FMLCommonSetupEvent` 中注册一次。RSI 在加载完成时冻结注册，
不接受运行期动态注册。

```java
package com.example.mymod;

import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.RegistrationResult;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod.EventBusSubscriber(
        modid = MyMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD
)
public final class MyRsiRegistration {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(MyRsiRegistration.class);

    private MyRsiRegistration() {}

    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(MyRsiRegistration::register);
    }

    private static void register() {
        RSIIntegrationApi api =
                RSIIntegrationApi.get().orElse(null);
        if (api == null) {
            LOGGER.error("RSI API is unavailable");
            return;
        }
        if (!api.version().supports(1, 1, 0)) {
            LOGGER.error(
                    "RSI API 1.1 or newer is required, found {}",
                    api.version()
            );
            return;
        }

        requireAccepted(api.registerRecipe(
                new MySmeltingRecipeIntegration()));
        requireAccepted(api.registerMachine(
                new MySmeltingMachineIntegration()));
        // 如果配方使用 RecipeExecutionKind.CUSTOM，再注册 execution。
    }

    private static void requireAccepted(
            RegistrationResult result
    ) {
        if (result.accepted()) return;
        throw new IllegalStateException(
                "RSI registration failed: status="
                        + result.status()
                        + ", id=" + result.registeredId()
                        + ", diagnostics=" + result.diagnostics()
        );
    }
}
```

注册失败不会抛出异常，而是通过 `RegistrationResult` 返回。发布版本至少应记录
`status`、`registeredId` 和 `diagnostics`，不要忽略注册结果。

同类型集成发生 claim 冲突时，`RegistrationOptions.priority` 更高者胜出；最高优先级
并列会被视为 ambiguous，不会静默选择一个。除非确实需要覆盖低优先级兼容实现，
否则使用 `RegistrationOptions.defaults()`。`attributes` 当前不参与配方或机器路由，
不能用来传递执行所必需的运行期状态。

## 3. 必须先理解的稳定语义

### 3.1 五种 ID

| ID | 含义 | 要求 |
|---|---|---|
| `recipeId` | datapack 中真实配方的 ID | 必须来自 live `Recipe#getId()` |
| `processId` | 配方与机器之间的路由键 | 同一个处理过程必须始终使用同一 ID |
| `entryId` | 配方内一个材料需求的身份 | 同一 `RecipeView` 内不可重复且跨 reload 保持稳定 |
| `portId` | 一个真实输出端口的身份 | 同一 `RecipeView` 内不可重复 |
| `integrationId` | 配方、机器或执行适配器的身份 | 使用自己的 namespace，发布后不要复用 |

`processId` 不是机器方块 ID。一个机器族可以接受多个 process，一个 process 也可以由
多个兼容机器承接。

### 3.2 物品身份

1.20.1 中物品身份由“注册 ID + NBT”组成。使用
`IntegrationResourceKey.item(ItemStack)` 构造物品键，使用
`IntegrationResourceKey.fluid(FluidStack)` 构造流体键。不要使用显示名称、hover
文本、`toString()` 或自定义字符串拼接作为身份。

`ResourceMatchMode` 的语义如下：

| 模式 | 匹配规则 |
|---|---|
| `ANY_IDENTITY` | 只要求相同物品或流体 ID，忽略 NBT |
| `EXACT_IDENTITY` | 要求完整身份，包括 NBT |
| `PARTIAL_IDENTITY` | 以声明的 NBT 为模板，实际资源必须包含模板内容 |

只要你的配方声明的是运行时 NBT 变体，就必须明确选择 `EXACT_IDENTITY` 或
`PARTIAL_IDENTITY`。空 NBT 不能作为部分匹配模板。

### 3.3 revision 与 reload

`RecipeContext.recipeRevision()` 标识本次配方视图对应的一代 recipe manager 数据。
当配方定义改变时，旧计划必须失效，不能继续使用旧的材料表结算。

以下变化必须反映到 revision 或 fingerprint：

- datapack reload 后配方材料或产物改变；
- 机器端口、槽位、容量、执行模式改变；
- 概率、returned container、target output 规则改变；
- 会改变输出身份或可执行性的模组配置改变。

不要只递增 revision 名称而保持内容不变。RSI 会校验 recipe definition fingerprint
和 machine descriptor fingerprint。

### 3.4 线程

| 回调或调用 | 执行线程 |
|---|---|
| `registerRecipe/registerMachine/registerExecution` | Mod 总线 common setup |
| `RecipeIntegration.supports` | 配方扫描线程，禁止访问世界 |
| `RecipeIntegration.inspect` | 默认服务端主线程 |
| `projectionThread() == BACKGROUND_SAFE` 时的 `inspect` | 后台安全线程 |
| `RecipeAvailabilityProvider.check` | 服务端主线程 |
| `RecipeIntegration.resolveJeiRecipe` | 客户端主线程 |
| `MachineIntegration.bindings`、`supports` | 机器发现与 claim，禁止读取世界状态 |
| `MachineIntegration.describe` | 服务端主线程 |
| `MachineIntegration.internalItemHandler` | 服务端主线程 |
| `MachineStateProvider.readState` | 服务端主线程，只读 |
| `createPlan/start/resume` | 服务端主线程 |
| `RunningOperation` 的 observe/collect/cleanup | 服务端主线程 |
| Consumer API 调用 | 拥有目标 `ServerPlayer` 的服务端主线程 |

只有当你能够保证 `inspect` 不读取 `Level`、`BlockEntity`、capability、玩家库存或
可变全局状态时，才返回 `RecipeProjectionThread.BACKGROUND_SAFE`。

不要在客户端发起材料扣减、订单结算或机器状态变更。客户端只负责展示、收集玩家输入和
发送请求。

## 4. 投影配方：RecipeIntegration

`RecipeIntegration<R>` 把一种 live recipe 转换成不可变的 `RecipeView`。一个集成只
应 claim 自己能正确解释的配方类型。

| `RecipeExecutionKind` | 用途 |
|---|---|
| `NONE` | 只展示或只参与查询，不由 RSI 执行 |
| `STANDARD_IO` | 由 RSI 标准输入输出执行器完成 |
| `CUSTOM` | 由 `RecipeExecutionIntegration` 完成 |

下面示例把带燃料需求的熔炼配方投影为物品输入和物品输出：

```java
package com.example.mymod;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.ItemIngredientView;
import com.huanghuang.rsintegration.api.recipe.MaterialRequirement;
import com.huanghuang.rsintegration.api.recipe.OutputAmount;
import com.huanghuang.rsintegration.api.recipe.OutputDeclaration;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.OutputProbability;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.api.recipe.RecipeFlags;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;

import java.util.List;
import java.util.Map;

public final class MySmeltingRecipeIntegration
        implements RecipeIntegration<SmeltingRecipe> {
    public static final ResourceLocation PROCESS_ID =
            new ResourceLocation("mymod", "smelting");
    private static final ResourceLocation INTEGRATION_ID =
            new ResourceLocation("mymod", "smelting_recipes");
    private static final String INPUT_GROUP = "ingredient";
    private static final String FUEL_GROUP = "fuel";
    private static final String OUTPUT_GROUP = "result";

    @Override
    public ResourceLocation id() {
        return INTEGRATION_ID;
    }

    @Override
    public boolean supports(Recipe<?> recipe) {
        return recipe instanceof SmeltingRecipe
                && recipe.getId() != null;
    }

    @Override
    public RecipeExecutionKind executionKind() {
        return RecipeExecutionKind.STANDARD_IO;
    }

    @Override
    public RecipeInspectionResult inspect(
            RecipeContext context,
            SmeltingRecipe recipe
    ) {
        if (recipe.getIngredients().isEmpty()
                || recipe.getIngredients().get(0).isEmpty()) {
            return RecipeInspectionResult.rejected(
                    diagnostic("missing_input"));
        }
        ItemStack output =
                recipe.getResultItem(context.registries()).copy();
        ResourceLocation recipeId = recipe.getId();
        if (recipeId == null) {
            return RecipeInspectionResult.rejected(
                    diagnostic("missing_recipe_id"));
        }
        if (output.isEmpty()) {
            return RecipeInspectionResult.rejected(
                    diagnostic("missing_output"));
        }

        Ingredient ingredient = recipe.getIngredients().get(0);
        MaterialRequirement input = new MaterialRequirement(
                entryId(recipeId, "input"),
                ItemIngredientView.anyOf(ingredient.getItems()),
                1L,
                InputConsumption.consumed(),
                INPUT_GROUP,
                0,
                true
        );
        MaterialRequirement fuel = new MaterialRequirement(
                entryId(recipeId, "fuel"),
                ItemIngredientView.anyOf(
                        new ItemStack(Items.COAL),
                        new ItemStack(Items.CHARCOAL)
                ),
                1L,
                InputConsumption.fuel(200L),
                FUEL_GROUP,
                1,
                true
        );
        OutputDeclaration result = new OutputDeclaration(
                new ResourceLocation(
                        recipeId.getNamespace(),
                        recipeId.getPath() + "/result"
                ),
                OUTPUT_GROUP,
                IntegrationResourceKey.item(output),
                OutputAmount.exactly(output.getCount()),
                OutputKind.PRIMARY,
                OutputProbability.guaranteed(),
                false,
                false,
                null
        );
        RecipeView view = new RecipeView(
                recipeId,
                PROCESS_ID,
                List.of(input, fuel),
                List.of(),
                List.of(result),
                new RecipeFlags(
                        true,
                        false,
                        true,
                        false,
                        true,
                        false,
                        true
                )
        );
        return new RecipeInspectionResult(view, List.of());
    }

    private static ResourceLocation entryId(
            ResourceLocation recipeId,
            String suffix
    ) {
        return new ResourceLocation(
                recipeId.getNamespace(),
                recipeId.getPath() + "/" + suffix
        );
    }

    private static IntegrationDiagnostic diagnostic(
            String reason
    ) {
        return new IntegrationDiagnostic(
                IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED,
                IntegrationSeverity.ERROR,
                INTEGRATION_ID,
                Map.of("reason", reason)
        );
    }
}
```

实现 `inspect` 时必须遵守以下规则：

- `recipeId` 使用 live recipe 的真实 ID。
- `processId` 必须与机器 binding 接受的 process 一致。
- 材料的 `amount` 是单次配方的真实消耗量，必须大于 0。
- 产物只声明配方真实产生的物品或流体。
- 不得在 `RecipeView` 中保存 live `Recipe`、`Ingredient`、`ItemStack` 或机器对象。
- 不可枚举的 predicate 不能伪装成有限的 `ItemIngredientView`。
- 同一 `RecipeView` 中 `entryId` 和 `portId` 都必须唯一。

### 4.1 Ingredient 展开

`Ingredient#getItems()` 返回当前注册状态下可枚举的替代物品。标签型 Ingredient 必须
在此处展开为可规划的真实物品集合。不要把标签名本身当作物品身份。

如果某个 Ingredient 运行时才能决定且无法枚举，应该拒绝投影并返回稳定诊断，而不是
返回一个永远不会匹配成功的假物品。

### 4.2 RecipeFlags

`RecipeFlags` 不是描述性备注，构造 `RecipeView` 时会与材料、输出交叉校验。

| 字段 | 正确值 |
|---|---|
| `recursivePlanning` | 该配方是否允许 RSI 递归补齐材料 |
| `targetProduction` | 是否至少有一个输出声明为 `targetProducing` |
| `deterministicOutput` | 存在概率输出时必须为 `false` |
| `runtimeDependentIdentity` | 是否存在运行时决定身份的输出 |
| `requiresFuel` | 是否存在 `InputConsumption.Kind.FUEL` |
| `supportsBatching` | 机器能否接收一次性聚合投料并累计完成全部确定性输出 |
| `orderedInputs` | 槽位顺序是否属于配方语义 |

`supportsBatching=true` 时，RSI 会在输入端口容量允许的范围内把多个操作的材料合并成
一次机器投料，并等待所有保证输出累计出现后才完成这批操作。机器必须真的能处理聚合
后的堆叠数量；如果只能逐次投一个材料，应保持 `false`。

## 5. RecipeView 语义

### 5.1 材料消耗类型

| `InputConsumption.Kind` | 用途 |
|---|---|
| `CONSUMED` | 普通消耗材料 |
| `FUEL` | 燃料，必须声明燃烧 tick |
| `CATALYST` | 参与操作但不应按普通产物返还的催化材料 |
| `TRANSFORMED` | 输入变成关联输出，必须指定 `linkedOutputPortId` |
| `CONTAINER_RETURNED` | 容器输入产生关联的空容器或残留物 |
| `DURABILITY` | 按耐久消耗，声明每次操作的损伤值 |

在 `STANDARD_IO` 中，`amount` 始终表示每次操作需要投料的物理数量。`FUEL` 的
`fuelBurnTicksPerOperation` 描述机器完成该次操作需要的燃烧时间，但标准 I/O
不会把炉内剩余燃烧时间当成可再次插入的物品；因此多操作订单会按操作次数预留和
投料，避免预览按燃料热值换算、执行却逐次插入的两套语义。

容器返回值必须使用 `containerReturned(portId)` 并链接到一个
`OutputKind.CONTAINER` 或 `OutputKind.REMAINDER` 输出。不要同时声明一个普通消耗的
桶和一个凭空生成的空桶。

### 5.2 输出声明

`OutputDeclaration` 描述机器真实会产生的输出，不是 RSI 可以代发的奖励表。

| 字段 | 说明 |
|---|---|
| `portId` | 输出端口稳定身份 |
| `outputGroup` | 与机器 output 端口匹配的分组 |
| `resource` | 物品或流体身份模板 |
| `amount` | 最小和最大数量 |
| `outputKind` | 主产物、副产物、容器、剩余物、transform 等 |
| `probability` | 固定、概率或运行时概率模型 |
| `dynamicIdentity` | 最终 NBT/流体 NBT 在运行时才能确定 |
| `targetProducing` | 输出由本次请求的目标资源决定 |
| `sourceInputEntryId` | returned/transformed 输出对应的输入 |

概率输出必须使用 `OutputProbability.probabilistic(...)` 或
`OutputProbability.dynamic(...)`。机器执行失败或概率未命中时，不得根据配方模板补发
产物；RSI 只接受执行器实际收集到的输出。

### 5.3 可选可用性检查

如果配方是否可规划取决于玩家权限、研究、维度或其他服务端状态，实现
`RecipeAvailabilityProvider`：

```java
@Override
public Optional<RecipeAvailabilityProvider> availabilityProvider() {
    return Optional.of(context -> {
        if (isUnlockedFor(context.player())) {
            return RecipeAvailability.available();
        }
        return new RecipeAvailability(
                false,
                false,
                List.of(lockedDiagnostic())
        );
    });
}
```

该检查在服务端主线程执行。不可用时至少返回一个诊断。

## 6. 声明机器：MachineIntegration

`MachineIntegration` 描述玩家可绑定的真实方块和选中方块后的端口。

```java
package com.example.mymod;

import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.InputOutputAccess;
import com.huanghuang.rsintegration.api.machine.ItemPortAccess;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.MachineItemPort;
import com.huanghuang.rsintegration.api.machine.PlannedConcurrency;
import com.huanghuang.rsintegration.api.machine.SupportScope;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MySmeltingMachineIntegration
        implements MachineIntegration {
    private static final ResourceLocation INTEGRATION_ID =
            new ResourceLocation("mymod", "smelting_machines");
    private static final ResourceLocation MACHINE_ID =
            new ResourceLocation("mymod", "furnace");

    @Override
    public ResourceLocation id() {
        return INTEGRATION_ID;
    }

    @Override
    public List<MachineBinding> bindings() {
        return List.of(MachineBinding.blocks(
                Set.of(MySmeltingRecipeIntegration.PROCESS_ID),
                true,
                new ResourceLocation("mymod", "furnace")
        ));
    }

    @Override
    public Map<ResourceLocation, PlannedConcurrency>
    planningConcurrency() {
        return Map.of(
                MySmeltingRecipeIntegration.PROCESS_ID,
                PlannedConcurrency.machineSlot()
        );
    }

    @Override
    public boolean supports(BlockEntity blockEntity) {
        return blockEntity instanceof AbstractFurnaceBlockEntity;
    }

    @Override
    public MachineDescriptorResult describe(
            MachineContext context,
            BlockEntity blockEntity
    ) {
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity)) {
            return MachineDescriptorResult.rejected(
                    new IntegrationDiagnostic(
                            IntegrationDiagnosticCode.MACHINE_UNSUPPORTED,
                            IntegrationSeverity.ERROR,
                            INTEGRATION_ID,
                            Map.of("reason", "not_a_furnace")
                    ));
        }
        return MachineDescriptorResult.ready(new MachineDescriptor(
                MACHINE_ID,
                1L,
                Set.of(MySmeltingRecipeIntegration.PROCESS_ID),
                List.of(
                        new MachineItemPort(
                                "input",
                                0,
                                null,
                                InputOutputAccess.INPUT,
                                ItemPortAccess.CONTAINER,
                                "ingredient",
                                null,
                                64
                        ),
                        new MachineItemPort(
                                "fuel",
                                1,
                                null,
                                InputOutputAccess.INPUT,
                                ItemPortAccess.CONTAINER,
                                "fuel",
                                null,
                                64
                        ),
                        new MachineItemPort(
                                "result",
                                2,
                                null,
                                InputOutputAccess.OUTPUT,
                                ItemPortAccess.CONTAINER,
                                null,
                                "result",
                                64
                        )
                ),
                List.of(),
                List.of(),
                MachineExecutionMode.OUTPUT_DELTA_ONLY,
                false,
                ConcurrencyCapabilities.machineSlot(),
                new SupportScope(List.of(), List.of(), false),
                true
        ));
    }

}
```

### 6.1 bindings

`bindings()` 只声明稳定身份，不得读取世界状态或扫描附近方块。

```java
MachineBinding.blocks(
        Set.of(processA, processB),
        true,
        new ResourceLocation("mymod", "basic_machine"),
        new ResourceLocation("mymod", "advanced_machine")
);
```

对于无法由固定方块 ID 表达的兼容方块，可使用
`MachineBinding.blockClasses(...)`。不要依赖 integration ID 的 namespace 推断方块。

`supportsGui=true` 只表示该方块存在可远程打开的原生菜单，不代表必须支持 GUI。

### 6.2 端口访问

| `ItemPortAccess` | 使用场景 |
|---|---|
| `CONTAINER` | 方块实体本身实现 `Container` |
| `ITEM_HANDLER` | 指定方向的 `IItemHandler` capability |
| `MACHINE_INVENTORY` | 固定内部槽位，必须实现 `internalItemHandler` |
| `CUSTOM` | 仅允许 `MachineExecutionMode.CUSTOM` |

`MACHINE_INVENTORY` 端口的 `side` 必须为 `null`。`internalItemHandler` 返回的槽位号
必须与 descriptor 中的端口槽位一致。

流体端口使用 `FluidPortAccess.FLUID_HANDLER` 或 `CUSTOM`。机器私有气体、温度、应力、
研究点等资源不能伪装成带特殊 NBT 的无意义物品。

### 6.3 执行模式

| 模式 | 适用机器 |
|---|---|
| `IMMEDIATE_ON_INSERT` | 插入完成后立即结算的机器 |
| `TICKED_AND_BUSY` | 有明确 busy/progress 状态的多 tick 机器 |
| `OUTPUT_DELTA_ONLY` | 没有可靠 busy 状态，但输出槽可观察增量的机器 |
| `CUSTOM` | 需要完整自定义生命周期 |

`TICKED_AND_BUSY` 必须提供 `MachineStateProvider`。busy 状态必须报告真实
`activeRecipeId`，并且 `stateRevision` 随状态变化递增。

```java
@Override
public Optional<MachineStateProvider> stateProvider() {
    return Optional.of((context, blockEntity) -> {
        if (!(blockEntity instanceof MyMachineBlockEntity machine)
                || machine.isRemoved()) {
            return MachineState.invalid();
        }
        return new MachineState(
                true,
                true,
                machine.isBusy(),
                machine.progress(),
                machine.totalProgress(),
                machine.activeRecipeId(),
                machine.activeProcessId(),
                machine.stateRevision(),
                null
        );
    });
}
```

`MachineStateProvider` 只读状态，不得投料、抽取、清理、缓存 `BlockEntity` 或修改世界。

### 6.4 descriptor revision

端口、槽位、容量、执行模式、support scope 或输出语义改变时必须提升
`descriptorRevision`。运行时选中的 descriptor 还会与计划中的 fingerprint 比较；
不一致时旧计划会被拒绝。

## 7. STANDARD_IO 执行合同

满足以下条件时优先使用 `RecipeExecutionKind.STANDARD_IO`：

- 配方材料可以完整枚举；
- 机器输入槽和输出槽都有稳定端口；
- 输入组和输出组能一一匹配；
- 可以检测真实输出是否出现；
- 机器不会把额外副产物写进无法隔离的共享槽；
- 输入和输出资源即使相同，也能通过槽位和输出基线区分。

标准执行会按以下顺序处理一次操作：

1. 校验 recipe view、descriptor revision 和 fingerprint。
2. 模拟目标槽位与输出容量。
3. 从选中的存储快照中预留材料。
4. 从玩家库存或连接存储中提取材料。
5. 按 `inputGroup` 和端口写入机器。
6. 等待机器完成或检测输出增量。
7. 只收集输出声明允许且实际出现的产物。
8. 处理容器返还、失败回滚和订单结算。

需要完整生命周期、无法显式映射端口、需要 GUI 交互、多方块或私有资源时，改用
`CUSTOM`，不要通过增加伪造端口来绕过标准合同。

## 8. CUSTOM 执行

自定义执行必须同时注册：

- 一个 `RecipeIntegration`，返回 `RecipeExecutionKind.CUSTOM`；
- 一个 `MachineIntegration`，返回 `MachineExecutionMode.CUSTOM` 和真实端口；
- 一个 `RecipeExecutionIntegration`。

### 8.1 回调顺序

| 回调 | 责任 |
|---|---|
| `createPlan` | 从当前 `RecipeView`、`MachineContext` 和预留材料建立真实执行计划 |
| `start` | 首次执行准备；返回 `STARTED`、`RETRY` 或 `REJECTED` |
| `RunningOperation.observe` | 报告机器当前阶段、进度和活动 recipe |
| `RunningOperation.collect` | 只报告上次之后真实产生的新输出，并处置材料 |
| `RunningOperation.snapshot` | 保存可跨重启恢复的可序列化状态 |
| `RunningOperation.onFailure` | 失败清理并报告每个材料的最终状态 |
| `RunningOperation.onCancel` | 取消清理并报告每个材料的最终状态 |
| `RunningOperation.onSettled` | 只在结算完成后做最后清理，不得生成新产物 |

`start` 必须按 `operationId` 幂等。已经启动的操作不能因为网络重试、同一 tick 重入或
计划重新加载而再次执行物理动作。

如果机器尚未就绪，可返回：

```java
return ExecutionResult.retry(
        20,
        preparationDiagnostic("machine_not_ready")
);
```

`retryAfterTicks` 只对 `ExecutionStartStatus.RETRY` 有效。

### 8.2 ExecutionPlan

`ExecutionPlan` 必须与当前上下文一致：

- `operationId` 来自 `ExecutionRequestContext`；
- recipe、machine、recipe integration 和 machine integration ID 与 claim 一致；
- `recipeRevision`、`descriptorRevision` 和两个 fingerprint 来自当前对象；
- item/fluid route 只能使用已预留的 `ReservedMaterial`；
- output port 必须与 `RecipeView.outputs()` 一一对应；
- support scope 和 concurrency 与 descriptor 一致。

不要在计划中直接保存 `Level`、`ServerPlayer`、`BlockEntity` 或 capability。

### 8.3 收集与处置

`OperationCollection.collectionSequence` 从 1 开始并严格递增。每次 `collect` 只能报告
上次收集之后实际出现的新产物。`complete=true` 前不能视为执行完成。

每个预留材料最终必须有且只有一个处置：

| `MaterialOutcome` | 含义 |
|---|---|
| `RECOVERED` | 已真实退还到可退款位置 |
| `CONSUMED` | 已确认消耗且不能退款 |
| `UNCERTAIN` | 状态未知，禁止乐观退款 |

`cleanupComplete=false` 或存在 `UNCERTAIN` 时，RSI 不会把操作当作可安全回滚。

### 8.4 快照与恢复

`OperationSnapshot.state()` 只允许保存：

- primitive；
- `String` 和稳定 ID；
- `CompoundTag` 中的可序列化状态。

不得保存 Java callback、`Level`、`ServerPlayer`、capability、handler 或旧
`RunningOperation`。服务端重启后会重新 claim recipe、machine 和 execution，再调用
`resume`。无法复核的状态必须进入 uncertain cleanup，不能伪造成功。

## 9. 规划期并发

`MachineDescriptor.concurrency()` 只有在选中具体方块后才能读取。
`MachineIntegration.planningConcurrency()` 用于在绑定具体方块前声明 process 级并发
合同：

```java
@Override
public Map<ResourceLocation, PlannedConcurrency>
planningConcurrency() {
    return Map.of(
            MySmeltingRecipeIntegration.PROCESS_ID,
            PlannedConcurrency.machineSlot()
    );
}
```

一个 process 的声明覆盖该 `MachineIntegration` 下所有接受此 process 的 binding。
如果任意一个支持该 process 的集成没有声明，规划会保守地保持 exclusive。

`supportOffsets` 是绑定具体方块前的最坏位置集合，只用于规划期冲突检测。选中机器后，
执行期以 `MachineDescriptor.supportScope()` 返回的真实 support/adjacent 位置为准。
因此规划期可以声明一个较大的候选范围，但 `describe()` 必须返回当前方块实际占用的
位置，不能把整个候选范围继续当成每台机器都独占的区域。

多机器声明只有在材料所有权、输出所有权、清理合同和副作用可证明兼容时才能并行。
声明与运行期 descriptor 冲突时仍会降级为 exclusive。

不要为了提速而把 `UNKNOWN` 改成 `machineSlot()`。并发的收益不能覆盖重复扣料、输出
归属不明或清理不可分离的风险。

## 10. JEI

JEI 专用展示对象不一定能直接交给服务端 `RecipeManager`。如果目标模组使用独立的 JEI
recipe DTO，重写：

```java
@Override
public Optional<Recipe<?>> resolveJeiRecipe(
        Object jeiRecipe,
        ItemStack displayed,
        RegistryAccess registries
) {
    if (!(jeiRecipe instanceof MyJeiRecipe myRecipe)) {
        return Optional.empty();
    }
    Recipe<?> liveRecipe = findLiveRecipe(
            myRecipe.recipeId(),
            displayed,
            registries
    );
    return Optional.ofNullable(liveRecipe);
}
```

该方法在客户端主线程调用，只负责把展示对象解析成可识别的真实 recipe。返回空表示
该 API 集成不支持此展示对象，不会猜测一个不存在的 recipe ID。

要让 JEI 加号出现，以下条件必须同时满足：

- 目标配方被你的 `RecipeIntegration` claim；
- 返回的 `processId` 能匹配玩家已绑定的机器；
- `resolveJeiRecipe` 能返回服务端可重新找到的真实 recipe；
- 配方 inspection 没有被拒绝；
- 玩家已绑定支持该 process 的真实机器。

## 11. Resonance Inventory API

Resonance Inventory API 用于第三方物品、武器、饰品或工具把共振盘中的物品视为额外的
玩家物品来源。它不把共振盘伪装成玩家主背包，也不会替换 Forge 的全局
`ITEM_HANDLER` capability。

入口是：

```java
Optional<RSIResonanceApi> api = RSIResonanceApi.get();
```

所有带 `ServerPlayer` 的调用必须在服务端主线程执行。共振盘只对当前玩家可见的已连接
共振存储生效，不读取其它玩家或未连接网络的数据。

### 11.1 打开短生命周期快照

```java
RSIResonanceApi api = RSIResonanceApi.get().orElse(null);
if (api == null) return;

ResonanceResult<ResonanceInventory> opened =
        api.openInventory(player);
if (!opened.successful()) {
    LOGGER.debug("RSI resonance storage unavailable: {}",
            opened.diagnostics());
    return;
}
ResonanceInventory inventory = opened.value();
```

`ResonanceInventory` 只能在当前请求或当前 tick 内使用。不要跨 tick、跨玩家或跨线程
缓存。需要再次访问时应重新调用 `openInventory`。

### 11.2 查询与匹配

```java
List<ResonanceEntry> exact = inventory.find(
        template,
        ResourceMatchMode.EXACT_IDENTITY
);
boolean present = inventory.hasItem(
        template,
        ResourceMatchMode.PARTIAL_IDENTITY
);
long count = inventory.count(
        template,
        ResourceMatchMode.ANY_IDENTITY
);
```

| 模式 | 语义 |
|---|---|
| `ANY_IDENTITY` | 只比较物品注册 ID，忽略 NBT |
| `EXACT_IDENTITY` | 比较物品注册 ID 和完整 NBT |
| `PARTIAL_IDENTITY` | 物品相同，且实际 NBT 包含模板中的字段 |

`ResonanceEntry.stack()` 返回独立副本。`entryId` 只在生成它的快照及 revision 内有效，
不能写入存档。

### 11.3 模拟提取与实际提取

需要消耗共振盘物品时，必须先模拟再执行：

```java
ResonanceEntry entry = exact.get(0);
ResonanceResult<ResonanceExtraction> simulated =
        inventory.extract(entry.entryId(), 1, true);
if (!simulated.successful()) return;

ResonanceResult<ResonanceExtraction> extracted =
        inventory.extract(entry.entryId(), 1, false);
if (!extracted.successful()) return;
ItemStack actual = extracted.value().stack();
```

实际提取按逻辑槽位和完整 NBT 精确执行。若快照打开后盘内容发生变化，接口返回
`STALE_SNAPSHOT`，调用方应重新打开快照，不能把旧 `entryId` 用在新的 revision 上。

### 11.4 未分配插入

```java
ResonanceResult<ResonanceInsertion> insertion =
        inventory.insert(stack, false);
ItemStack remainder = insertion.value().remainder();
```

插入的物品不会自动占用背包逻辑槽位；它进入共振盘的未分配内部条目。是否接受以及
保留多少由真实后端决定，`remainder` 表示未插入部分。

### 11.5 使用边界

- 不把共振盘物品写回玩家 `Inventory`。
- 不注册覆盖玩家 item capability 的全局 handler。
- 不长期保存 `ResonanceInventory`、`ResonanceEntry` 或可变 `ItemStack` 引用。
- 不在客户端根据展示快照直接扣除物品；实际提取只能由服务端执行。
- API 不可用时保持原模组行为，不要把“不可用”解释成背包里一定有或一定没有。

## 12. Consumer API

Consumer API 用于只消费 RSI 业务能力的模组。入口是：

```java
RSICraftConsumerApi api =
        RSICraftConsumerApi.get().orElse(null);
if (api == null
        || api.state() != ConsumerServiceState.READY) {
    return;
}
```

所有 Consumer 调用都必须在服务端主线程执行，并使用该玩家自己的 `ServerPlayer`。
不要从客户端线程、异步 HTTP 线程或另一个玩家的上下文提交订单。

### 12.1 能力限制

`api.capabilities()` 返回当前服务端的限制。不要硬编码：

- 最大分页大小；
- 单次 inspection 的最大配方数；
- 每玩家或全局最大活动 session；
- 最大计划节点和搜索状态；
- 单批最大订单数；
- session、plan 和订单幂等记录过期 tick。

调用方应先读取能力，再裁剪自己的请求。

`api.storageOptions(player)` 返回当前玩家可见的存储 ID。只有调用方确实要限定某个
存储后端时才使用 `ConsumerStorageSelector.Explicit`；普通场景使用
`ConsumerStorageSelector.Default()`，只使用玩家背包时使用
`ConsumerStorageSelector.PlayerInventoryOnly()`。

### 12.2 查询配方目录

```java
public static ConsumerResult<ConsumerRecipeCatalogPage> findRecipes(
        ServerPlayer player,
        ItemStack output
) {
    RSICraftConsumerApi api =
            RSICraftConsumerApi.get().orElse(null);
    if (api == null) {
        return ConsumerResult.rejected(
                ConsumerDiagnostic.error(
                        ConsumerDiagnosticCode.CATALOG_NOT_READY,
                        "RSI consumer API is unavailable",
                        true
                )
        );
    }
    ConsumerRecipeQuery query = new ConsumerRecipeQuery.ByOutput(
            IntegrationResourceKey.item(output),
            ResourceMatchMode.ANY_IDENTITY
    );
    return api.findRecipes(
            player,
            new ConsumerRecipeCatalogRequest(query, 32, 0L)
    );
}
```

`nextCursor == null` 表示目录页结束。查询新页时继续使用响应中的 `cursor` 和同一个
query。`recipeRevision` 改变时，旧目录 cursor 不再可信，应从头重新查询。

### 12.3 创建 inspection

```java
ConsumerRecipeKey key = new ConsumerRecipeKey(
        recipeId,
        null,
        new ConsumerRequestAmount.Executions(1),
        ConsumerPlanningPreferences.empty()
);
ConsumerInspectionRequest request =
        new ConsumerInspectionRequest(
                consumerId,
                new ConsumerInspectionRequestId(requestUuid),
                List.of(key),
                new ConsumerMachineSelector.AutomaticBound(),
                new ConsumerStorageSelector.Default(),
                ConsumerOutputDestination.PLAYER_INVENTORY,
                new ConsumerInspectionOptions(64, true)
        );
ConsumerResult<ConsumerInspectionTicket> opened =
        api.openInspection(player, request);
```

`consumerId` 代表你的模组业务身份。`requestId` 代表一次逻辑检查请求；网络重试必须
复用同一 ID。

inspection 会冻结材料、计划、机器绑定和存储快照。分页期间不要混用其他请求的快照。

### 12.4 拉取 inspection 页

```java
ConsumerResult<ConsumerInspectionPage> pageResult =
        api.nextInspectionPage(player, ticket.ticketId());
if (!pageResult.successful()) {
    return;
}
ConsumerInspectionPage page = pageResult.value();
if (page.state() == ConsumerInspectionPageState.PENDING
        || page.state() == ConsumerInspectionPageState.RUNNING) {
    // 稍后使用同一 ticketId 再拉取，不要重新 openInspection。
    return;
}
for (ConsumerCraftability result : page.results()) {
    if (result.canSubmit()) {
        ConsumerPlanId planId = result.planId();
        // 保存 planId，下一阶段提交订单。
    }
}
```

`PENDING` 和 `RUNNING` 页不会携带半成品结果。`nextPageIndex != null` 时继续使用同一
ticket。READY 结果一定有 `planId`，非 READY 结果不会暴露计划。

不再需要某个 inspection 时调用 `closeInspection(player, ticketId)`。如果已经生成
READY plan 但决定不下单，可调用：

```java
api.discardPlan(player, consumerId, planId);
```

`closeInspection` 和 `discardPlan` 的成功结果使用 `ConsumerAck.accepted()` 判断。

### 12.5 提交订单

```java
ConsumerExternalRequestId externalRequestId =
        new ConsumerExternalRequestId(logicalOrderUuid);
ConsumerOrderRequest request = new ConsumerOrderRequest(
        consumerId,
        externalRequestId,
        planId
);
ConsumerResult<ConsumerOrderReceipt> submitted =
        api.submit(player, request);
```

幂等范围是：

```text
player + consumerId + externalRequestId
```

网络超时、连接重置或 UI 重试必须复用同一个 `externalRequestId`。新订单必须生成新
ID。已消费的 plan 不能再次提交。

批量提交：

```java
List<ConsumerOrderRequest> orders = planIds.stream()
        .map(planId -> new ConsumerOrderRequest(
                consumerId,
                new ConsumerExternalRequestId(planId.value()),
                planId
        ))
        .toList();
ConsumerBatchOrderRequest batch =
        new ConsumerBatchOrderRequest(
                consumerId,
                new ConsumerBatchRequestId(batchUuid),
                orders
        );
ConsumerResult<ConsumerBatchOrderReceipt> receipt =
        api.submitBatch(player, batch);
```

批次中的每个订单仍保留自己的幂等结果。不要因为整批响应失败就给全部订单生成新 ID。

### 12.6 查询与取消

```java
ConsumerResult<ConsumerOrderStatus> status =
        api.status(player, consumerId, orderId);

ConsumerResult<ConsumerCancelResult> cancelled =
        api.cancel(
                player,
                consumerId,
                orderId,
                new ConsumerExternalRequestId(cancelRequestUuid)
        );
```

取消请求也必须使用稳定幂等 ID。`cancel` 成功返回只表示请求已被接受，最终状态仍以
`status` 为准。终态包括 `SUCCEEDED`、`FAILED` 和 `CANCELLED`。

### 12.7 结果与诊断

`ConsumerResult.successful()` 判断业务是否成功。失败时读取
`ConsumerDiagnostic.code()`、`severity()`、`detail()` 和 `retryable()`，不要解析异常
字符串。

`idempotentReplay=true` 表示本次响应来自同一幂等请求的历史结果，不是第二次物理执行。
调用方可以把它当作成功处理，但不应再触发一次扣料或产出。

如果要自行封装网络包，使用 `ConsumerApiCodecs` 读写 DTO。未知 wire version 会明确
拒绝，不能静默按旧格式继续解析。

## 13. 错误处理

### 13.1 注册阶段

| 状态 | 处理 |
|---|---|
| `ACCEPTED` | 注册成功 |
| `REJECTED` | 声明无效或 ID 重复，开发阶段应阻止发布 |
| `FROZEN` | 注册窗口已关闭，改为在 common setup 注册 |
| `PHASE_INVALID` | API 尚未进入注册阶段或服务已停止 |

不要把注册失败降级为“运行时再试一次”。RSI 不支持运行期动态注册。

### 13.2 配方不可用

| 诊断方向 | 常见原因 |
|---|---|
| `RECIPE_CLAIM_AMBIGUOUS` | 多个集成同优先级 claim 同一配方 |
| `RECIPE_INSPECTION_FAILED` | recipe view 构造失败或返回 rejected |
| `MATERIAL_DECLARATION_INVALID` | 材料数量、分组或消耗类型不合法 |
| `OUTPUT_DECLARATION_INVALID` | 输出数量、端口或概率模型不合法 |
| `TARGET_OUTPUT_INVALID` | target producing 输出与请求目标不一致 |

### 13.3 机器不可用

| 诊断方向 | 常见原因 |
|---|---|
| `MACHINE_UNSUPPORTED` | 方块没有绑定到机器集成 |
| `MACHINE_CLAIM_AMBIGUOUS` | 多个机器集成同时匹配 |
| `MACHINE_DESCRIPTOR_INVALID` | 端口重复、分组不匹配或模式冲突 |
| `MACHINE_DESCRIPTOR_CHANGED` | 计划生成后机器结构或 revision 改变 |
| `MACHINE_STATE_INVALID` | busy/progress/activeRecipe 矛盾 |
| `PORT_BINDING_UNAVAILABLE` | descriptor 声明的端口在运行时不存在 |

### 13.4 执行不可用

| 诊断方向 | 常见原因 |
|---|---|
| `EXECUTION_PREPARATION_FAILED` | start 无法准备机器 |
| `EXECUTION_CONTRACT_VIOLATION` | route、plan 或 collection 违反合同 |
| `COLLECTION_SEQUENCE_INVALID` | collect sequence 重复或倒退 |
| `OUTPUT_MISSING` | 机器没有产生声明中的保证输出 |
| `MATERIAL_DISPOSITION_INVALID` | 预留材料没有完整处置 |
| `EXECUTION_CLEANUP_UNCERTAIN` | 无法证明材料已回收 |

### 13.5 共振盘不可用

| 诊断方向 | 常见原因 |
|---|---|
| `API_UNAVAILABLE` | RSI 未完成初始化，或调用发生在公共入口安装前 |
| `PLAYER_UNAVAILABLE` | 玩家已退出、已移除或上下文不属于在线玩家 |
| `MAIN_THREAD_REQUIRED` | 从异步线程或错误线程打开或修改快照 |
| `STORAGE_UNAVAILABLE` | 功能关闭、没有已连接的共振盘或当前玩家看不到存储 |
| `ENTRY_NOT_FOUND` | `entryId` 不属于当前快照 |
| `STALE_SNAPSHOT` | 打开快照后共振盘内容或 revision 已变化 |
| `EXTRACTION_FAILED` | 后端无法按完整 NBT 和逻辑条目精确提取 |
| `INSERTION_FAILED` | 后端插入失败；调用方应保留未插入余额 |

`INVALID_REQUEST` 表示参数本身错误，例如空物品、非正数量或超过条目数量。
标记为 `retryable=true` 的诊断可以重新打开快照后重试；不可重试错误不应循环重试。

## 14. 版本规则

`RSIIntegrationApi.version()`、`RSIResonanceApi.version()` 和
`RSICraftConsumerApi.version()` 返回各自公共 API 的版本。运行前可用
`supports(major, minor, patch)` 检查最低要求。

同一 major 内：

- 可以新增默认方法；
- 可以新增独立可选接口；
- 可以新增诊断 code；
- 不应向已发布 record 追加构造参数；
- 不应改变已有字段的单位、空值或概率含义；
- 不应复用已发布的 ID 表示不同语义。

破坏性变化进入下一个 major。第三方不应依赖 method descriptor、类名、reflection 或
内部网络 discriminator 绕过公共 API。

上述规则描述当前兼容策略，不构成永久维护、回移修复或支持所有历史 API 版本的承诺。
RSI 可以在新的 major 中引入不兼容变更；升级 RSI 的第三方需要在运行时检查版本并根据
最新接口完成适配。第三方继续使用已发布的官方版本时，不会因后续版本升级而失去该版本
已经授予的 API 使用许可。

## 15. 发布前检查清单

### 注册与线程

- 只在 common setup 注册一次；
- RSI 缺失时不会加载直接引用 API 的类；
- recipe、machine、execution ID 使用自己的 namespace；
- 所有回调遵守本文线程表；
- `supports` 和 `bindings` 不读取可变世界状态。

### 配方

- 声明的材料、燃料、容器和输出都来自真实 recipe；
- Ingredient 已展开为可枚举物品；
- NBT 使用正确的 match mode；
- `entryId`、`portId` 和输出分组稳定且唯一；
- `RecipeFlags` 与材料、输出一致；
- 概率或动态输出没有在失败时补发。

### 机器

- binding 能匹配玩家实际绑定的方块；
- descriptor 端口和 `internalItemHandler` 槽位一致；
- output group 与配方 output group 一致；
- 执行模式与真实机器状态一致；
- revision 和 fingerprint 会随结构变化更新。

### 执行

- `start` 按 `operationId` 幂等；
- 等待使用 `RETRY`，不是伪造失败；
- `observe` 报告真实 busy/progress；
- `collect` 只报告真实新增产物；
- 每个预留材料最终唯一处置；
- cleanup 不确定时不会乐观退款；
- snapshot 不包含世界对象或 callback。

### Consumer

- 调用发生在服务端主线程；
- 分页使用同一 ticket；
- 网络重试复用 `ConsumerExternalRequestId`；
- 新订单不复用旧幂等 ID；
- 提交前只接受 `READY` craftability；
- 批次使用 `submitBatch`，不伪造多个独立 session；
- 取消和状态查询始终带当前 player 与 consumer ID。

### Resonance

- 只调用已安装的 `RSIResonanceApi.get()`；
- 所有调用发生在服务端主线程；
- 快照只在当前请求或当前 tick 内使用；
- 需要消费时先模拟，再按同一 `entryId` 和 revision 实际提取；
- 遇到 `STALE_SNAPSHOT` 时重新打开快照，不复用旧条目；
- 插入后的 `remainder` 被完整保留并交给调用方处理；
- 不把共振盘冒充玩家背包或全局 `ITEM_HANDLER`。

## 16. 最小验收场景

发布前至少完成以下真实环境验证：

1. 在 JEI 中找到你的配方，并确认加号能在已绑定机器上出现。
2. 用单次配方完成一次递归计划，确认缺料提示与真实材料一致。
3. 准备刚好足够的材料，确认成功后没有重复返还。
4. 准备一组缺少材料，确认失败前不会扣减完整输入。
5. 在机器运行中破坏机器、卸载区块或 reload datapack，确认旧计划失效。
6. 对概率配方运行多次，确认只有真实出现的输出被结算。
7. 对容器输入运行配方，确认空容器和剩余物位置正确。
8. 使用 Consumer catalog、inspection、submit、status、cancel 跑通一次完整订单。
9. 对同一 order 请求重复提交，确认返回幂等结果而不是再次执行。
10. 同时绑定两台机器，确认 process 路由不会把配方送到错误机器。
11. 打开共振盘快照，分别验证 ANY、EXACT、PARTIAL 三种身份匹配。
12. 先模拟提取再实际提取；确认模拟不改变数量，实际提取按完整 NBT 精确扣除。
13. 在两个 API 调用之间修改共振盘内容，确认旧快照返回 `STALE_SNAPSHOT`。
14. 插入超过剩余容量的物品，确认 `remainder` 数量准确且没有丢失。
