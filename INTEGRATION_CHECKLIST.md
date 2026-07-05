# 模组机器集成自查清单

每集成一个新模组机器，按以下流程逐项完成。

---

## 零、集成类型速查

先判断你的机器属于哪种，不同路径步骤不同：

| 类型 | 特征 | Delegate | supportsGui | 示例 |
|------|------|----------|-------------|------|
| **GUI 容器** | 右键打开 GUI，有 Menu/Screen | 自定义 | `true` | Crock Pot, Smithing Table |
| **世界交互（tick 型）** | 丢物品/放物品到方块上，有进度条 | 自定义 | `false` | Spirit Altar, NecroBrazier |
| **世界交互（即时型）** | 手持物品右键即完成合成，无进度 | 自定义 | `false` | Runic Workbench |

---

## 一、集成流程（必须逐项完成）

### 步骤 1：解析 JAR，弄清机器结构

用 `jar tf` + `javap -c -p` 分析目标模组的 JAR：

```
# 找 BlockEntity
jar tf <mod>.jar | grep -i "BlockEntity"

# 看继承链、槽位、IItemHandler、配方引用
javap -c -p -cp <mod>.jar com.example.YourBlockEntity

# 找配方类
jar tf <mod>.jar | grep -i "Recipe"
javap -c -p -cp <mod>.jar com.example.YourRecipe
```

**必须搞清楚：**
- 几个槽位，每个槽位干什么（input/fuel/output）
- 有没有 `IItemHandler` capability（`getCapability` 是否暴露）
- 合成是 tick 驱动还是即时完成
- 配方是标准 `Recipe<?>` 还是自定义类型，输入/输出字段叫什么

### 步骤 2：Recipe Handler

文件：`src/main/java/com/huanghuang/rsintegration/recipe/<Mod>RecipeHandler.java`

实现 `ModRecipeHandler` 接口：

```java
public class XXXRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId("your_mod_id"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("com.example.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // 尝试 getResultItem() / getResult() / getOutput() 方法
        // 兜底：反射读 output 字段
    }

    @Override @Nullable
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        // 反射提取所有输入材料 → List<IngredientSpec>
    }
}
```

在 `XXXRSModule.registerRecipeHandler()` 注册：
```java
ModRecipeHandlers.register(new XXXRecipeHandler());
```

**自查：** `getResultItem` 和 `getIngredients` 是否判空了？配方有无副产物（`getRemainingItems`）？

### 步骤 3：Batch Delegate

文件：`src/main/java/com/huanghuang/rsintegration/mods/<mod>/XXXBatchDelegate.java`

实现 `IBatchDelegate`（或继承 `AbstractBatchDelegate`）：

```java
public class XXXBatchDelegate extends AbstractBatchDelegate implements IBatchDelegate {
    // validateAndInit — 验证机器存在、空闲、配方匹配
    // getRequiredMaterials — 返回配方所需材料
    // tryStartSingleCraft — 抽材料、填入机器、启动合成
    // tryStartWithMaterials — 链式路径（材料已预扣）
    // isCraftComplete — 轮询合成是否完成
    // collectResult — 收集产物
    // onBatchFailed / onBatchFinished — 清理、退款
    // getMachinePos — 返回机器坐标
}
```

**关键规则：**
- 燃料补满而非全量提取（先检查已有，只补差额）
- 非标准燃料在 `GenericCraftPacket.addFuelIfNeeded` 中写入
- 跨 tick 轮询时**每次重新获取** BlockEntity 和 IItemHandler（禁止缓存）
- 共享账本模式下**不要**在 `onBatchFailed` 退款（链式任务统一退）
- 修改 BE 后必须调 `be.setChanged()`
- 如果是即时合成（无 tick 进度），仍需实现自定义 delegate 与方块交互

### 步骤 4：ModType 注册

在 `XXXRSModule.registerModType()` 中：

```java
ModType.register("your_mod_id",
    new String[]{"com.example.recipe.YourRecipe"},  // recipe 类名前缀
    new String[]{"your_machine_block"},              // blockKey 关键词
    new String[0],                                    // blockKey 前缀
    YourBatchDelegate::new);                          // delegate factory
```

**参数说明：**
- `recipePrefixes` — 最长前缀匹配，用于配方分类
- `blockKeyKeywords` — 从绑定方块反查 ModType
- `blockKeyPrefixes` — 额外前缀匹配
- `delegateFactory` — delegate 构造器，始终传自定义 delegate 的构造引用

### 步骤 5：JEI 元数据（configureJei）

在 `registerModType()` 中调用：

```java
ModType.configureJei("your_mod_id",
    // UID → filter: JEI 分类 UID 映射到绑定 filter
    new String[][]{{"modid:jei_category_uid", "your_filter"}},
    // 类名 → ModType: 配方类名到 ModType ID 的映射
    new String[][]{{"com.example.recipe.", "your_mod_id"}},
    // 工具提示 i18n key（可选，null = 无提示）
    "gui.rs_integration.jei.your_machine_craft");
```

**规则：**
- UID 数组：`{"uid"}` = filter 取 modId；`{"uid", "filter"}` = 显式指定 filter
- UID 数组传 `null` 表示无 JEI UID（纯类名驱动的模组）
- 类名数组：支持最长前缀匹配，越具体越靠前
- 第一个参数（id）**必须**与 `ModType.register()` 的 id 一致，否则配置静默丢弃

**多 filter 示例如（Malum）：**
```java
ModType.configureJei("malum",
    new String[][]{
        {"malum:spirit_infusion", "spirit_altar"},
        {"malum:spirit_focusing", "spirit_crucible"}
    },
    new String[][]{
        {"com.sammy.malum.common.recipe.SpiritFocusingRecipe", "spirit_crucible"},
        {"com.sammy.malum.common.recipe.SpiritInfusionRecipe", "spirit_altar"},
        {"com.sammy.malum.", "malum"}  // 通配兜底
    },
    null);
```

### 步骤 6：绑定目标注册

在 `XXXRSModule.registerBindingTargets()` 中：

```java
BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
    "modId",                       // 模组 ID
    ModType.byId("your_mod_id"),   // 关联 ModType
    RSIntegrationConfig.ENABLE_XXX, // config 开关
    List.of("com.example.YourBlock"), // 方块类名
    "your_filter",                 // 绑定 filter 字符串
    false                          // supportsGui
));
```

- `supportsGui = true` — 有容器 GUI（锻造台、熔炉、Crock Pot）
- `supportsGui = false` — 世界交互（祭坛、工作台即时合成）

### 步骤 7：GUI 远程打开（仅 `supportsGui = true`）

**A) 客户端 Mixin** — 绕过原版距离检测：
- `stillValid()` / `m_6875_()` → 客户端无条件返回 `true`
- `containerTick()` / `m_6190_()` → BE null 时返回安全默认值

**B) 配方预填充** — 在 `OpenBoundMachineGuiPacket` 中添加 prefill 方法。

**C) 事件总线注册** — 需要 `@SubscribeEvent` 的 Mixin，在 Module 中注册：
```java
@Override
public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
    return () -> () -> MinecraftForge.EVENT_BUS.register(XXXGuiClientEventHandler.class);
}
```

### 步骤 8：Config 开关

在 `RSIntegrationConfig.java` 中：
```java
public static ForgeConfigSpec.BooleanValue ENABLE_XXX;
// builder 中：
ENABLE_XXX = builder.define("enableXxx", true);
```

### 步骤 9：Module 入口

在 `RSIntegrationMod.java` 的 `MODULES` 列表添加：
```java
new ModuleEntry("modId", RSIntegrationConfig.ENABLE_XXX,
    () -> com.huanghuang.rsintegration.mods.xxx.XXXRSModule.INSTANCE)
```

### 步骤 10：MachineInteractType 分类（即时型世界交互）

如果机器无 GUI 且是即时合成，在 `MachineInteractType.fromBlockKey()` 中添加关键词：
```java
if (lower.contains("your_block_keyword"))
    return QUICK;
```

否则它会被默认归为 `GUI` 型，点击时尝试打开不存在的容器。

### 步骤 11：语言文件

`zh_cn.json` 和 `en_us.json` 中添加所有用户可见字符串。

---

## 二、JEI 按钮不显示 —— 排查顺序

1. **`getBindingFilter` 返回 null？** → `configureJei` 的 UID/类名映射是否匹配
2. **`findBinding` 没找到？** → filter 字符串是否与绑定注册时一致
3. **Config 开关 false？** → 检查 `ENABLE_XXX`
4. **模块未注册？** → `RSIntegrationMod.MODULES` 列表

---

## 三、常见陷阱速查

| 陷阱 | 后果 | 修复 |
|------|------|------|
| 跨 tick 缓存 BlockEntity | 刷物/吞物 | 每次 tick 重新 `level.getBlockEntity()` |
| 共享账本下单方面退款 | 双倍刷物 | `onBatchFailed` 检查 `usingSharedLedger` |
| 修改 BE 不调 `setChanged()` | 重启/崩服回档 | 改完必须调 |
| `entity.getItem().shrink()` 不跟 `setItem` | 掉落物数量客户端不同步 | shrink 后调 `entity.setItem(entity.getItem().copy())` |
| 给玩家退物品前不检查在线状态 | 物品掉虚空 | 检查 `!player.hasDisconnected()` |
| `configureJei` id 与 `register` id 不一致 | JEI 按钮静默消失 | 两个 id 必须严格一致 |
| 网络包处理不在 `enqueueWork` | 在 Netty 线程读写世界 → 崩溃 | 用 `context.enqueueWork(() -> {...})` |
| 机器坐标不校验绑定 | 全服隔空偷窃 | 服务端检查 `AltarBindingRegistry.isBound()` |

---

## 四、反射与类加载

- Class 对象在 `static` 块或 `ensureClasses()` 中一次性加载并缓存
- 反射字段/方法名同时覆盖 Mojang 名和 SRG 名（开发/生产环境不同）
- 使用 `ModClassLoader.ensureClasses(modId, ...)` 先做快速存在性检查

---

## 五、事务安全（Ledger）

```
Phase 1: reserve → Phase 2: commit → Phase 3: craft
                                      ↑
                           commit 失败 → 已 reserve 归零，物品未动
                           commit 成功 → 物品已消耗，craft 必须完成或退款
```

- **共享账本**：`usingSharedLedger = true` 时，退款由链式任务统一执行
- **独立账本**：`tryStartSingleCraft` 自己 create/commit/abort
- `abort()` 前检查 `network != null && network.canRun()`
- 退款给玩家前检查 `!player.hasDisconnected() && !player.isRemoved()`
