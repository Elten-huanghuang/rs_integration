# 工业级模组机器集成自查清单

每写完一个新的 `XXXBatchDelegate`、`XXXRSModule`、Mixin 或网络包，逐项核对。

---

## 零、新模组集成全周期注册流程（必读）

每集成一个新模组机器，需要按以下顺序踩过每一个注册点。缺任意一环都会导致功能残废——能绑定但没按钮、有按钮但无法合成、能合成但机器不工作。

### 0.1 整体架构速览

```
RS 终端 → JEI 点击 "+" → GenericCraftPacket → 服务端 AsyncCraftChain
                                                      ↓
                                            CandidateEngine 查 RecipeIndex
                                                      ↓
                                            StepExecutor 调用 BatchDelegate
                                                      ↓
                                            IBatchDelegate.tryStartSingleCraft()
                                                      ↓
                                            机器 BlockEntity 插/提物品 → 完成
```

JEI 按钮显示的先决条件链：
```
RecipeGuiLayoutsMixin.getBindingFilter() 返回非 null
  → ModType.filterForJeiUid(uid) 命中（步骤 0.4.1）
  → 或 ModType.filterForRecipeClass(className) 命中（步骤 0.4.2）
  → 或原版机器 / 特殊 UID（Mixin 内硬编码）
    → findBinding() 在玩家背包找到对应绑定物品
      → 按钮显示 ✓
```

### 0.2 注册清单总览（11 步）

| 步骤 | 内容 | 文件 | 有 GUI | 无 GUI |
|------|------|------|--------|--------|
| 1 | Recipe Handler | `recipe/XXXRecipeHandler.java` | ✓ | ✓ |
| 2 | Batch Delegate | `mods/xxx/XXXBatchDelegate.java` | ✓ | ✓ |
| 3 | ModType 注册 | `XXXRSModule.registerModType()` | ✓ | ✓ |
| 4 | JEI 元数据 | `configureJei()` 调用 | ✓ | ✓ |
| 5 | 绑定目标注册 | `XXXRSModule.registerBindingTargets()` | ✓ | ✓ |
| 6 | 远端 GUI 打开 | `OpenBoundMachineGuiPacket` / Mixin | ✓ | ✗ |
| 7 | Config 开关 | `RSIntegrationConfig.java` | ✓ | ✓ |
| 8 | Module 入口 | `RSIntegrationMod.java` MODULES 列表 | ✓ | ✓ |
| 9 | 计划阶段逻辑 | `GenericCraftPacket` / `PlanWarnings` | 按需 | 按需 |
| 10 | 语言文件 | `zh_cn.json` + `en_us.json` | ✓ | ✓ |
| 11 | JEI UID 常量 | `RecipeGuiLayoutsMixin.java` | 按需 | 按需 |

### 0.3 步骤详解

#### 步骤 1：Recipe Handler

实现 `ModRecipeHandler` 接口，告诉系统如何从配方对象提取输入材料和产出物。

```java
public class XXXRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId("your_mod_id"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().equals("com.example.YourRecipeClass");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        // 从配方提取输入材料列表
        // 如果配方的输入字段是私有的，用反射访问
    }
}
```

在 `registerRecipeHandler()` 中注册：
```java
ModRecipeHandlers.register(new XXXRecipeHandler());
```

**自查：** 输入材料的 `Ingredient` 是否判空了（`!ing.isEmpty()`）？配方产出物 `getResultItem()` 是否判空了？

#### 步骤 2：Batch Delegate

实现 `IBatchDelegate` 接口，定义机器的具体合成逻辑。

核心方法：
- `validateAndInit(player, level, pos, recipe)` — 初始化，验证机器状态
- `tryStartSingleCraft(inputs, toolInputs)` — 执行一次合成
- `isCraftComplete()` — 检查合成是否完成
- `collectResult()` — 收集产出物
- `onBatchFinished(success)` — 批次结束清理
- `getFuelSlotInfo()` — 返回燃料槽信息（可选）

**关键规则：**
- 燃料槽应补满而非全量提取（先检查已有数量，只补足差额）
- 非标准燃料（非熔炉可燃物）需在 `GenericCraftPacket.addFuelIfNeeded` 中写死目标燃料物品
- 燃料不足时先检查残余能量（如 `residualDye`），有余量就继续，没有才 abort
- 产物回收不要包含燃料槽和容器槽

#### 步骤 3：ModType 注册

在 `registerModType()` 中调用 `ModType.register()`：

```java
ModType.register("your_mod_id",
    new String[]{"com.example.recipe.YourRecipe"},  // recipePrefixes — classifyRecipe 用
    new String[]{"your_machine_keyword"},             // blockKeyKeywords — fromBlockKey 用
    new String[]{"your_prefix"},                      // blockKeyPrefixes — fromBlockKey 前缀匹配
    YourBatchDelegate::new);                          // delegateFactory
```

**注意：**
- `recipePrefixes` 用于最长前缀匹配分类配方，越具体的类名越靠前
- `blockKeyKeywords` 用于从绑定物品的 blockKey 反查 ModType
- 如果机器有特殊的 infer 模式（试错法），传第二个 delegateFactory

#### 步骤 4：JEI 元数据（configureJei）

**这是本次重构的核心。** JEI 元数据不再散落在 Mixin 的 if/else 链中，而是集中在 ModType 注册点。

```java
ModType.configureJei("your_mod_id",
    // UID → filter 映射：每个 JEI 分类 UID 对应哪个 binding filter
    new String[][]{{"modid:jei_category_uid"}},  // 单元素 [uid] → filter 默认取 modId
    // 或显式指定 filter：
    // new String[][]{{"modid:jei_uid", "your_filter_string"}},

    // 类名前缀 → filter 映射：JEI 不暴露标准 UID 时的兜底
    new String[][]{{"com.example.recipe.", "your_mod_id"}},

    // 工具提示 i18n key（可选）
    "gui.rs_integration.jei.your_machine_craft");
```

**UID→filter 映射规则：**
- `new String[][]{{"uid"}}` — 单元素，filter 默认取 ModType 的 `id`
- `new String[][]{{"uid", "filter"}}` — 双元素，显式指定 filter（当 filter ≠ modId 时）
- `null` — 无 UID 映射（仅靠类名 fallback 的模组，如 TLM、TACZ）

**类名→filter 映射规则：**
- 支持最长前缀匹配，越具体的类名越靠前
- 同一 ModType 可以有多个不同 filter 的类名前缀（如 Avaritia：`avaritia_crafting` / `_compressor` / `_smithing`）
- `null` — 无类名映射（仅靠 UID 的模组）

**filter 字符串的约定：**
- filter 是 `getBindingFilter()` 的返回值，也是 `findBinding(filter)` 的搜索关键词
- filter 必须与绑定目标注册时的 blockKey 中的某个片段匹配
- 一个 ModType 可以有多个 filter（如 Malum：`spirit_altar` + `spirit_crucible`）
- filter 不再等于 ModType 的 `id` 是常见且正确的情况（如 embers_alchemy ModType，filter 是 `embers`）

**多 filter 的 ModType 示例（Malum）：**
```java
ModType.configureJei("malum",
    new String[][]{
        {"malum:spirit_infusion", "spirit_altar"},
        {"malum:spirit_focusing", "spirit_crucible"}
    },
    new String[][]{
        {"com.sammy.malum.common.recipe.SpiritFocusingRecipe", "spirit_crucible"},
        {"com.sammy.malum.common.recipe.SpiritInfusionRecipe", "spirit_altar"},
        {"com.sammy.malum.", "malum"}  // 通用 fallback
    },
    null);
```

**null UID 的 ModType 示例（TLM，纯类名驱动）：**
```java
ModType.configureJei("touhou_little_maid",
    null,  // 无 JEI UID
    new String[][]{{"com.github.tartaricacid.touhoulittlemaid.", "touhou_little_maid"}},
    null);
```

**自查：** `configureJei` 的第一个参数（id）必须与此前 `ModType.register()` 的 id 一致，否则 `byId()` 返回 GENERIC 导致调用被静默跳过。

#### 步骤 5：绑定目标注册

在 `registerBindingTargets()` 中注册哪些方块可以被 RS 终端绑定：

```java
BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
    "mod_display_name",           // 显示用的模组名
    ModType.byId("your_mod_id"),  // 关联的 ModType
    RSIntegrationConfig.ENABLE_XXX, // config 开关
    List.of("com.example.YourMachineBlock"),  // 方块类名列表
    "your_filter_string",         // 绑定用的 filter 字符串
    true/false                    // supportsGui：是否有可远程打开的 GUI
));
```

**supportsGui 的含义：**
- `true` — 机器有容器 GUI（如锻造台、熔炉），JEI 会显示 "Machine GUI" 按钮，点击可远程打开
- `false`（默认）— 机器是世界中交互（如祭坛、多方块结构），只能通过 RS 自动合成操作

**GUI 机器的额外要求（见步骤 6）。**

**自查：**
- blockKey 的 filter 字符串是否与 `configureJei` 中的 filter 一致？
- 同一个物理方块可以被多个 ModType 注册（如 HephaestusForgeBlock 被 fa 和 fa_smithing 注册）
- Component 类方块（多方块结构组件）应注册为 `supportsGui = false`

#### 步骤 6：远端 GUI 打开（仅 GUI 机器）

如果 `supportsGui = true`，需要处理两方面：

**A) 客户端 Mixin — 绕过原版 GUI 的距离/状态检测**

机器的 `Menu` 或 `Screen` 中：
- `stillValid()` / `m_6875_()` — 距离检测需在客户端无条件返回 `true`
- `containerTick()` / `m_6190_()` — BlockEntity null 检查，返回安全默认值
- 参考：`OpenBoundMachineGuiPacket.java` 中的 `prefillXXX` 方法

**B) 配方预填充**

如果机器 GUI 打开后需要自动填充配方材料（如锻造台的模板/基底/附加物），在 `OpenBoundMachineGuiPacket` 中添加对应的 `prefillXXX` 方法。

**C) 事件总线注册**

如果 Mixin 使用 `@SubscribeEvent` 监听客户端事件，需要在对应 Module 的 `clientInitSupplier()` 中注册：
```java
@Override
public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
    return () -> () -> MinecraftForge.EVENT_BUS.register(XXXGuiClientEventHandler.class);
}
```

#### 步骤 7：Config 开关

在 `RSIntegrationConfig.java` 中添加：
```java
// 字段声明
public static ForgeConfigSpec.BooleanValue ENABLE_XXX;

// builder 中定义
ENABLE_XXX = builder.define("enableXxx", true);
```

**注意：** `ForgeConfigSpec.define()` 对缺失的 key 回退到默认值——用户不需要删除旧 config 文件即可获得新开关。

#### 步骤 8：Module 入口

在 `RSIntegrationMod.java` 的 `MODULES` 列表中添加：
```java
new ModuleEntry("mod_id", RSIntegrationConfig.ENABLE_XXX,
    () -> com.huanghuang.rsintegration.mods.xxx.XXXRSModule.INSTANCE)
```

MODULES 列表按需加载——config 开关为 false 时整个模块不初始化。

#### 步骤 9：计划阶段逻辑（按需）

**GenericCraftPacket.java** — 如果机器需要特殊燃料计划：
```java
// 在 planCrafting 方法的 addFuelIfNeeded 段中添加
YourBatchDelegate.addFuelIfNeeded(recipeModType != null ? recipeModType.id() : null,
    itemAvailable, itemSource, neededCounts, repeatCount);
```

**PlanWarnings.java** — 添加该模组的 case 分支。

#### 步骤 10：语言文件

`zh_cn.json` 和 `en_us.json` 中必须添加所有用户可见的字符串：

**必须添加：**
- `"gui.rs_integration.jei.your_machine_craft": "通过 XXX 远程合成"` — JEI 按钮工具提示
- `"rsi.xxx.warning": "..."` — 用户可见的警告/错误消息
- `"rsi.xxx.no_fuel": "..."` — 燃料不足消息

**自查：** 每新增一个 JEI 工具提示 key，是否在两个语言文件中都添加了？缺少语言条目 = 按钮悬停时空白提示。

#### 步骤 11：JEI UID 常量（按需 — 现在很少需要）

**重构后，绝大多数模组的 JEI UID 不需要在 Mixin 中声明常量。**

只有当以下情况才需要在 `RecipeGuiLayoutsMixin.java` 中添加 UID 常量：
- 该 UID 在 `getBindingFilter()` 之外也被引用（如 `FA_HEPHAESTUS_SMITHING_UID` 在 `extractFaSmithingBaseItem` 中使用）
- 原版机器的 UID（`minecraft:smelting` 等），这些没有 ModType 注册
- 特殊路由逻辑需要的 UID（如 `SMITHING_UID` 需要根据配方类名分派到 FA/Avaritia/原版）

**不要**再为普通模组添加 UID 常量——它们通过 `ModType.filterForJeiUid(uid.toString())` 自动查找。

---

### 0.4 JEI 按钮不显示：排查顺序

JEI 不显示 "+" / "Machine GUI" 按钮时，按以下顺序排查：

1. **`getBindingFilter` 是否返回 null？** → 检查 `ModType.filterForJeiUid` + `ModType.filterForRecipeClass` 是否有匹配。在 `configureJei` 中检查 UID 映射是否正确。
2. **`findBinding` 是否找到绑定？** → 检查玩家背包中是否有对应 filter 的绑定物品。注意 filter 字符串必须与绑定目标注册时的 blockKey 片段一致。
3. **Config 开关是否为 true？** → `ForgeConfigSpec.define()` 对缺失 key 回退默认值，通常不是问题。
4. **模块是否在 MODULES 列表中？** → 检查 `RSIntegrationMod.java`。

---

## 一、事件总线注册 (Event Bus Wiring)

**这是新手向进阶过渡时必交的学费。** Forge 的 `@SubscribeEvent` 注解**不会自动生效**——如果没有注册，监听器就是死代码。

- [ ] **静态订阅者已注册：** 使用 `@Mod.EventBusSubscriber` 注解，或在主类中显式调用 `MinecraftForge.EVENT_BUS.register(YourClass.class)`。
- [ ] **实例订阅者已注册：** 单例类用 `MinecraftForge.EVENT_BUS.register(getInstance())`，客户端 lambda 用 `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () -> MinecraftForge.EVENT_BUS.register(...))`。
- [ ] **客户端隔离：** 客户端专属事件（`ClientPlayerNetworkEvent.LoggingOut`、`ScreenEvent` 等）必须只在物理客户端注册，否则服务端会崩。
- [ ] **注销对称：** 注册了 `register(instance)` 的地方，是否有对应的 `unregister(instance)`？（玩家下线、配置热重载时）

**自查方法：** `grep -r "@SubscribeEvent" src/` 找到所有带注解的类，逐一确认它们在事件总线上注册过。

---

## 二、跨域与网络通信安全 (Network & Security)

- [ ] **主线程同步 (Thread Safety):** 所有的自定义网络包（Packet/Message）处理逻辑，是否已经严格使用 `context.enqueueWork(() -> { ... })` 包裹？（绝对禁止在 Netty 线程直接读写世界或实体）。
- [ ] **坐标防篡改 (Spoofing Defense):** 服务端收到客户端发来的机器操作请求（坐标 X,Y,Z）时，是否进行了严格的 `AltarBindingRegistry.isBound(key, pos, player)` 校验？（防黑客全服隔空偷窃）。
- [ ] **维度解析稳健：** 客户端传来的维度 ID 是否经过 `ResourceLocation.tryParse()` 和 `server.getLevel()` 校验？空维度、无效维度不应导致 NPE。
- [ ] **拒绝同步强载 (Async Chunk Checking):** 在做环境探测（比如 `isBindingFresh` 或检测多方块结构）时，是否使用了 `level.isLoaded(pos)` 乐观跳过？（绝对禁止对未加载的区块调用 `level.getChunk(pos)` 或 `ChunkUtils.loadChunk`，防 TPS 冰封）。
- [ ] **DDOS 限流 (Rate Limiting):** 客户端可频繁触发的网络包（如合成预览、配方查询），是否在服务端加了基于玩家 UUID 的漏桶/令牌桶限流？（防连点器撑爆网络通道）。

---

## 三、界面渲染与客户端欺骗 (GUI & Rendering)

- [ ] **幽灵进度防御 (Null BlockEntity Guard):** 目标机器的 Menu 类中，所有直接读取 `this.blockEntity` 的方法（如 `getCookingProgress`），是否已使用 Mixin `@Inject(cancellable = true)` 做好 Null 检查并返回安全默认值 `0` 或 `0.0F`？
- [ ] **客户端脱战防御 (StillValid Bypass):** 目标机器的客户端屏幕心跳（`containerTick`）或原版容器（`stillValid`）距离检测，是否已在客户端无条件返回 `true`？（防界面闪一下就关）。
- [ ] **NBT 图标补全 (Icon NBT Injection):** 在绑定目标机器提取展示图标时，是否已将该机器的 `BlockEntityTag`（并清洗掉内部巨型 Inventory 数据以防发包溢出）塞入 `ItemStack`，以防 TACZ 等 NBT 驱动模组渲染成紫黑块？
- [ ] **UI 导航栈健壮：** 远程打开机器 GUI → 关闭时返回 RS 终端的导航栈，是否基于 `Minecraft.setScreen(null)` 拦截而非 `Screen.removed()` 回调？（`Screen.removed()` 在子菜单替换时也会触发，导致误判。）

---

## 四、玩家背包与掉落物同步 (Inventory Desync & Entities)

- [ ] **玩家背包更新发包 (Inventory Sync):** 在服务端通过代码直接修改玩家物品（如 `split()`, `shrink()`, `grow()`）后，是否**紧接着**调用了 `player.getInventory().setChanged()` 与 `player.inventoryMenu.broadcastChanges()`？（防"幽灵物品"假象）。
- [ ] **实体物品数据触发 (ItemEntity Sync):** 在修改地上掉落物 `ItemEntity` 的数量时（包括 `shrink()`、`grow()`、`setCount()`），是否调用了 `entity.setItem(entity.getItem().copy())` 来触发底层 `SynchedEntityData` 发包？（防地上的物品数量视觉不同步）。
  > **警示：** `entity.getItem().shrink(n)` 不会自动发包——调用后必须立刻跟 `entity.setItem(entity.getItem().copy())`，否则客户端看到的物品数量不会变化。
- [ ] **满载防炸服切片 (Drop Stack Truncation):** 当 RS 网络和玩家背包双双爆满，不得不把合成产物作为掉落物喷吐时，是否使用了 `getMaxStackSize()` 循环切割拆包？（防单次生成几千个实体引发服务器宕机）。

---

## 五、事务两阶段提交与防刷物 (Ledger Transaction Safety)

- [ ] **共享账本退款隔离 (Double Refund Defense):** 在任务失败（`onBatchFailed`）或者机器中断（`recoverFromPedestals`、`clearFilledPedestals`）时，是否判断了 `if (usingSharedLedger)`？
  > **警示：** 共享账本模式下，退款由链式任务（Chain）统一负责，单个 Delegate 绝对不能将撤下的物品插回网络，否则 100% 触发双倍刷物漏洞。
- [ ] **在线状态核验 (Void Offline Refund):** 在执行最后的 `giveItemToPlayer` 给玩家退回零头前，是否核验了 `player != null && !player.hasDisconnected() && !player.isRemoved()`？（防玩家断线瞬间物品掉入虚空）。
- [ ] **Capability 即时刷新 (LazyOptional Invalidation):** 跨 Tick 的自动合成中，每 Tick 执行 `insertItem` 或 `extractItem` 前，是否**重新获取**了 `BlockEntity` 与其 `IItemHandler`？（绝对禁止跨 Tick 缓存，防方块被挖走导致的刷物/吞物）。
- [ ] **网络失效后退 (Network Fallback):** `abort()` 退款时，是否检查了 `network != null && network.canRun()`？如果网络已失效（控制器被拆除），是否将物品直接退给玩家而非走 RS API？

---

## 六、第三方机器"隐形回档"防御 (Third-Party Ghost Saves)

- [ ] **强制硬盘保存标记 (SetChanged Directive):** 在通过反射或其他手段强行修改了第三方机器（`BlockEntity`）的进度、内部物品或能量池后，是否**必定执行了** `be.setChanged()`？
  > **警示：** 这是防重启/崩服回档的最关键指令，甚至重于客户端发包。
- [ ] **客户端视觉同步 (Block Update):** 修改 BE 的状态会影响方块外观（如祭坛激活/熄灭）时，是否调用了 `level.sendBlockUpdated(pos, oldState, newState, 3)` 强制客户端重绘？
- [ ] **容器伪装兼容性 (Fake Container Typing):** 如果需要借用原版配方系统的副产物机制（`getRemainingItems`），是否使用了原版的 `TransientCraftingContainer` 而不是自定义空壳？（防第三方作者强制类型转换导致的 `ClassCastException`）。
- [ ] **多方块组件类型核验 (Multi-block Component Typing):** 调用第三方 API 获取多方块结构中的"pedestal / 组件"列表时，是否对每个条目做了实际类型校验（如 `isSpiritCrucible` 过滤），而不是盲信返回结果？
  > **警示：** 第三方模组的多方块 API（如 `capturePedestals()`）可能在特定结构配置下意外返回核心机器自身或其他无关方块。每一种组件类型都必须显式过滤，绝不能假设列表里只有正确的 pedestal。

---

## 七、反射与类加载安全 (Reflection & Class Loading)

- [ ] **SRG/MojMap 双名兼容：** 反射访问字段/方法时，是否同时尝试了 Mojang 名和 SRG 名？（MCP 环境下两者不同，漏一种会导致开发/生产不一致。）
- [ ] **Class.forName 缓存：** 所有反射用到的类（`Class<?>` 对象）是否在 `static` 块或 `ensureClasses()` 中一次性加载并缓存，而非在热路径（`tryStartSingleCraft`、`tick()`）中重复调用 `Class.forName`？
- [ ] **`ensureClasses` 模式：** 每个 Delegate 是否有独立的 `ensureClasses()` 方法，且用 `ModClassLoader.ensureClasses(modId, ...)` 先做快速存在性检查再逐项加载？
- [ ] **字段名变更兼容：** 反射读取的字段名是否考虑了第三方模组版本升级导致的字段重命名？（如 Malum 的 `IngredientWithCount` → 在 1.6.6+ 改为公开 API，`getRequiredMaterials` 已做双分支兼容。）

---

## 八、配方解析与递归防护 (Recipe Resolution Safety)

- [ ] **DFS 循环检测 (Cycle Detection):** 递归解析配方依赖树时，是否用 `HashSet<String>` 或 `LinkedHashSet<ResourceLocation>` 维护当前解析路径（压栈/弹栈），并在发现回环时立即跳出？
- [ ] **配方产出物安全判空：** `tryGetResultItem` 后是否检查了 `!result.isEmpty()` 再使用？部分配方（如 SmithingTrim）产出 `ItemStack.EMPTY` 是合法行为。
- [ ] **NBT 匹配精确性 (Ingredient NBT Guard):** 在虚拟库存供给原料时，`Ingredient.test()` 对于有无 NBT 的物品都会通过。是否需要额外的 `matchesIngredientTags()` 检查以避免 NBT 物品被误匹配？（如 RS 存储盘上有 NBT 数据的物品不应被虚拟库存错误消耗。）
- [ ] **副产物同步 (Secondary Outputs):** 多步合成链中，中间产物的 `getCraftingRemainingItem()` 和 `tryGetSecondaryOutputs()` 是否已纳入虚拟库存？（如合成蛋糕返还空桶。）

---

## 九、绑定系统与数据持久化 (Binding & Persistence)

- [ ] **数据本源不可变 (NBT on ItemStack):** 绑定数据是否存储在物品自身的 NBT 上（而非 WorldSavedData）？——物品在哪，数据就在哪，彻底告别世界坏档和跨服数据不同步。
- [ ] **Copy-on-Write 安全 (NBT Deep Copy):** 修改绑定物品的 NBT 时，是否先 `tag.copy()` 再 mutate 再 `stack.setTag(copy)`？（防 JEI 等客户端渲染线程异步读取导致 `ConcurrentModificationException`。）
- [ ] **内存缓存可重建 (Pure Cache):** `AltarBindingRegistry.BINDINGS` 等内存哈希表是否定位为"纯缓存"——即清空后可通过扫描玩家物品 NBT 完全重建？是否在 `ServerStoppedEvent` 时清空？
- [ ] **绑定有效期校验 (Freshness Check):** 使用绑定时是否调用了 `isBindingFresh()` 检查机器仍存在？（但不主动加载区块！）

---

## 十、内存生命周期管理 (Memory Lifecycle & Leaks)

- [ ] **安全并发遍历 (Concurrent Traversal):** 跨 Tick 执行的 `List` 集合（如任务调度器 `AsyncCraftManager`），是否已经使用了 `CopyOnWriteArrayList` 或 snapshot 模式？（防 `ConcurrentModificationException`）。
- [ ] **强加载票据回收 (Ticket Release):** 任何通过 UUID 申请的 `ForgeChunkManager.forceChunk` 凭证，在任务完成、失败、或超时清理时，是否**百分之百**被 `releaseForceLoad` 解除？
- [ ] **生命周期钩子扫除 (Event Hook Cleanup):**
  - 玩家下线（`PlayerLoggedOutEvent`）：定时器缓存是否清除？挂起的 `AsyncCraftChain` 是否取消并退款？远程 GUI 授权（`RemoteGuiAuth`）是否销毁？
  - 服务器停止（`ServerStoppingEvent`/`ServerStoppedEvent`）：所有活跃的合成链是否 `abortAll()`？`BINDINGS` 等静态缓存是否清空？`Diagnostics` 事件是否清空？
- [ ] **静态状态跨服污染 (Cross-Server Leak):** 客户端单例类（如 `MachineHub`）是否在 `ClientPlayerNetworkEvent.LoggingOut` 时清理状态？（加入单机存档 → 退出 → 加入服务器，静态字段仍保留上一个存档的数据。）

---

## 十一、诊断与性能监控 (Diagnostics & Performance)

- [ ] **诊断工具零分配（热路径）：** 从渲染循环或高频 Tick 调用的诊断查询方法（如 `recentEvents()`），是否使用脏标志缓存而非每帧 `List.copyOf()` 创建新列表？
- [ ] **日志字符串无反射：** 性能监控的 `snapshot()` 或高频日志是否使用 `StringBuilder`/`+` 拼接而非 `String.format()` ？（后者走正则 + 反射，极耗 CPU。）
- [ ] **主开关门控：** 所有诊断记录方法体第一行是否为 `if (!enabled) return;`？（默认关闭，零开销。）
- [ ] **环形缓冲区有界：** 诊断事件队列是否有 `MAX_EVENTS` 上限？（`ConcurrentLinkedDeque` + `while (size > MAX) removeFirst()` 模式。）

---

## 十二、JEI 集成与 ModType 解耦 (JEI Decoupling via ModType)

重构后，JEI 元数据从 `RecipeGuiLayoutsMixin` 迁移到各 Module 的 `configureJei()` 调用中。

- [ ] **`configureJei` 完整：** 新模组的 `registerModType()` 中是否已调用 `configureJei()`，包含 UID 映射 `String[][]` 和类名前缀映射 `String[][]`？
- [ ] **UID 映射与 filter 一致：** `configureJei` 中的 filter 字符串是否与 `registerBindingTargets` 中的 blockKey 片段一致？
- [ ] **多 filter 模组：** 如果一个 ModType 对应多个 filter（如 Malum: spirit_altar / spirit_crucible），是否在 `configureJei` 中分别映射了每个 UID 和类名前缀到正确的 filter？
- [ ] **null UID 模组：** TLM、TACZ 等没有 JEI 分类 UID 的模组，是否在 `configureJei` 中传 `null` 作为 UID 参数，并依赖类名 fallback？
- [ ] **不要往 Mixin 新增 UID 常量：** 新增模组的 JEI UID 常量**不需要**在 `RecipeGuiLayoutsMixin` 中声明，除非该 UID 在 `getBindingFilter` 之外还被引用。`getBindingFilter` 通过 `ModType.filterForJeiUid(uid.toString())` 自动查找。
- [ ] **类名 fallback 覆盖全面：** `configureJei` 的类名映射是否覆盖了该模组所有可能的配方类名？包括 JEI wrapper 类的全限定名。
- [ ] **No JEI without the entries:** 每新增一个 JEI 工具提示 key，必须同时在 `zh_cn.json` 和 `en_us.json` 里添加对应翻译条目。
- [ ] **`byId` 匹配：** `configureJei` 第一个参数（id）必须与 `ModType.register()` 的 id 严格一致，否则配置被静默丢弃。

---

> **最后的话：** 当你对着这个清单，自信地全部打上勾的时候，你写出来的模块，就是整个 Minecraft Modding 圈最顶级、最抗造的底层架构。这份清单凝结了数十轮深度审计中踩过的每一个坑——希望它成为你未来每一个新项目的护身符。
