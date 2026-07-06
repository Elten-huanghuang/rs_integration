# RS Integration 开发指南

本文档面向多人协作开发，覆盖项目架构、核心概念、集成流程、代码规范和常见陷阱。

---

## 一、项目概述

`rs-integration` 是 Refined Storage 的扩展模组，让玩家通过 RS 网络远程操作其他模组的机器——浏览配方、提交合成、自动收集产物。

核心流程：

```
JEI/REI 界面 → 侧边栏配方列表 → 选择配方 → 自动合成计划 → 
提取 RS 网络材料 → 绑定机器 → 填入材料/启动 → 轮询完成 → 收集产物入 RS
```

---

## 二、架构总览

```
src/main/java/com/huanghuang/rsintegration/
├── api/                    # 对外接口（ISmithingRecipeAccessor 等）
├── config/                 # 配置项（RSIntegrationConfig）
├── crafting/               # 合成调度核心
│   ├── batch/              #   IBatchDelegate 接口 + 链式调度
│   ├── plan/               #   合成计划生成
│   └── ExtractionLedger    #   事务账本
├── mixin/                  # Mixin 注入
│   ├── minecraft/          #   原版
│   ├── refinedstorage/     #   RS
│   └── jei/                #   JEI
├── mods/                   # 各模组集成代码（按 mod 分目录）
│   ├── goety/              #   Delegate + Packet + 辅助类
│   ├── malum/
│   └── ...
├── network/                # 网络包定义与注册
├── recipe/                 # ModRecipeHandler 配方适配层
├── reflection/             # 反射系统
│   ├── probes/             #   XXReflection 探针类（Class<?> 集中管理）
│   ├── contract/           #   ReflectionContract + ContractValidation（启动期校验）
│   └── probes/             #   17 个探针文件
├── sidepanel/              # RS 侧边栏 UI
└── util/                   # 工具类（Reflect, ModIds, ModClassLoader）
```

---

## 三、核心概念

### 3.1 ModType

`ModType` 是集成的最小注册单元，每个支持的模组/机器类型对应一个 `ModType` 实例。它关联：

- **配方分类**：按 recipe 类名前缀自动归类
- **Delegate 工厂**：创建处理该机器类型的 `IBatchDelegate`
- **JEI 元数据**：JEI 分类 UID ↔ 内部 filter 映射

```java
ModType.register("malum",
    new String[]{"com.sammy.malum.common.recipe."},  // 配方前缀
    new String[]{"spirit_altar"},                      // blockKey 关键词
    new String[0],                                      // blockKey 前缀
    MalumBatchDelegate::new);                           // delegate 构造引用
```

### 3.2 RecipeHandler（配方适配层）

`ModRecipeHandler` 负责从各模组的配方对象中提取输入材料和产出物。每个模组一个 Handler，通过反射读取配方字段/方法。

**`AbstractRecipeHandler` 基类**（`recipe/AbstractRecipeHandler.java`）消除了 ~18 个 Handler 中的重复 `canHandle` 代码。子类只需在 `static` 块中注册 recipe 类名前缀，`canHandle` 由基类统一实现：

```java
public final class MalumRecipeHandler extends AbstractRecipeHandler {
    static { registerRecipePrefixes(MalumRecipeHandler.class, "com.sammy.malum."); }

    @Override public ModType modType() { return ModType.byId("malum"); }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) { ... }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) { ... }
}
```

注册：`ModRecipeHandlers.register(new MalumRecipeHandler())`

自有 `canHandle` 逻辑的 Handler（如 Goety、Fa 等）仍可覆写。

### 3.3 BatchDelegate（合成委托）

`IBatchDelegate` 是机器交互的核心，每个机器类型一个实现。继承自 `AbstractBatchDelegate`（`crafting/batch/AbstractBatchDelegate.java`），负责：

| 方法 | 职责 |
|------|------|
| `validateAndInit()` | 验证机器存在、空闲、配方可执行 |
| `getRequiredMaterials()` | 返回合成所需全部材料 |
| `tryStartSingleCraft()` | 从网络提取材料 → 填入机器 → 触发合成 |
| `tryStartWithMaterials()` | 链式路径：材料已预扣，直接填机器 |
| `isCraftComplete()` | tick 轮询：合成是否完成 |
| `collectResult()` | 提取产物 |
| `onBatchFailed()` / `onBatchFinished()` | 清理、退款 |

**Delegate 不应缓存 BlockEntity / IItemHandler**——每次 tick 重新 `level.getBlockEntity(pos)` 获取。

**基类模板方法：** `AbstractBatchDelegate` 提供带保护的默认实现：
- `isCraftComplete(level)` — 内含 `level.isLoaded(pos)` 区块卸载保护，子类覆写 `isMachineCraftFinished(be)` 即可
- `onBatchFailed(player, reason)` — 内含 `usingSharedLedger` 防双倍退款保护，子类覆写 `clearMachineState(be, player)` 即可
- `warnOnce(state, format, args...)` — tick 路径限流日志，只在错误状态变更时输出，避免每秒 20 条刷屏

### 3.4 绑定系统（Binding）

玩家手持 RS 网络终端右键机器方块，建立"RS 控制器 → 机器"绑定关系。服务端通过 `AltarBindingRegistry` 存储，`RSAvailabilityChecker` 在提交合成前校验绑定有效性。

### 3.5 机器三类分法

| 类型 | 特征 | supportsGui | 示例 |
|------|------|-------------|------|
| **GUI 容器** | 右键打开 GUI，有 Menu/Screen | `true` | Crock Pot, Smithing Table |
| **世界交互（tick 型）** | 丢/放物品到方块，有进度条 | `false` | Spirit Altar, NecroBrazier |
| **世界交互（即时型）** | 手持物品右键即完成，无进度 | `false` | Runic Workbench |

即时型必须在 `MachineInteractType.fromBlockKey()` 中添加关键词，否则会错误尝试打开 GUI。

---

## 四、反射系统

### 4.1 设计原则

类名集中管理，字段/方法反射分散在调用点。

- **类名 → 探针**：所有 `Class.forName()` 调用收敛到 `reflection/probes/XXReflection.java`
- **字段/方法 → Reflect 工具类**：`Reflect.getField(be, "inventory")`、`Reflect.invoke(be, "init")`
- **启动期校验**：`ContractValidation.validateAll()` 在 `onCommonSetup` 中验证所有注册类存在性

### 4.2 探针文件模式

```java
@ModReflection(modId = ModIds.MALUM, description = "Malum spirit altar/crucible system")
public final class MalumReflection {
    public static Class<?> spiritAltarBEClass;
    public static Class<?> crucibleBEClass;
    // ...

    static {
        register("com.sammy.malum...SpiritAltarBlockEntity", "spiritAltarBEClass");
        register("com.sammy.malum...SpiritCrucibleCoreBlockEntity", "crucibleBEClass");
    }

    private static void register(String className, String fieldName) {
        // 注册 ReflectionContract + 关联目标 Field
    }
}
```

### 4.3 Delegate 中使用探针

```java
// 前：每个文件自己 Class.forName
private static Class<?> spiritAltarBEClass;
static { spiritAltarBEClass = Class.forName("com.sammy.malum..."); }

// 后：引用探针
import ...reflection.probes.MalumReflection;

if (MalumReflection.spiritAltarBEClass != null
    && MalumReflection.spiritAltarBEClass.isInstance(be))
```

### 4.4 新增探针

在 `reflection/probes/` 下新建文件，遵循以上模板。需满足：
- `@ModReflection` 注解，modId 使用 `ModIds` 常量
- 所有字段为 `public static Class<?>`（不存 `Field`/`Method`）
- `static { register(...) }` 连接 ContractValidation
- `public static boolean ready` + `isAvailable()`

---

## 五、新模组集成流程

### 5.1 解析 JAR

```bash
jar tf <mod>.jar | grep -i "BlockEntity"
javap -c -p -cp <mod>.jar com.example.YourBlockEntity
```

必须搞清楚：槽位数量与用途、IItemHandler 暴露情况、合成是 tick 驱动还是即时、配方类结构。

### 5.2 创建探针

在 `reflection/probes/` 新建 `XXReflection.java`，注册所有需要反射的类。

### 5.3 创建 RecipeHandler

`recipe/XXRecipeHandler.java`，实现 `ModRecipeHandler`，在对应 Module 中注册。

### 5.4 创建 BatchDelegate

`mods/<mod>/XXBatchDelegate.java`，实现 `IBatchDelegate`。

### 5.5 注册 ModType + JEI

在 Module 的 `registerModType()` 中调用 `ModType.register()` 和 `ModType.configureJei()`。

```java
ModType.configureJei("malum",
    new String[][]{
        {"malum:spirit_infusion", "spirit_altar"},
        {"malum:spirit_focusing", "spirit_crucible"}
    },
    new String[][]{
        {"com.sammy.malum.common.recipe.SpiritInfusionRecipe", "spirit_altar"},
        {"com.sammy.malum.", "malum"}  // 通配兜底
    },
    null);
```

**重要**：`configureJei` 的第一个参数（id）必须与 `ModType.register()` 的 id 严格一致。

### 5.6 注册绑定目标

```java
BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
    "modId", ModType.byId("your_mod_id"),
    RSIntegrationConfig.ENABLE_XXX,
    List.of("com.example.YourBlock"),
    "your_filter",
    false  // supportsGui
));
```

### 5.7 GUI 型机器的额外步骤

- **客户端 Mixin**：绕过原版距离检测（`stillValid()` 返回 true）
- **recipe 预填充**：`OpenBoundMachineGuiPacket` 中添加 prefill
- **事件总线注册**：在 Module `clientInitSupplier()` 中注册 GUI 事件处理器

### 5.8 Config + Module 入口

```java
// RSIntegrationConfig.java
public static ForgeConfigSpec.BooleanValue ENABLE_XXX;

// RSIntegrationMod.java — MODULES 列表
new ModuleEntry("modId", RSIntegrationConfig.ENABLE_XXX,
    () -> com.huanghuang.rsintegration.mods.xxx.XXXRSModule.INSTANCE)
```

### 5.9 语言文件

`zh_cn.json` 和 `en_us.json` 同步添加所有用户可见字符串。

---

## 六、代码规范

### 6.1 命名

- 模组目录：小写无分隔符（`goety`, `malum`, `youkaishomecoming`）
- Delegate 类：`<MachineName>BatchDelegate`
- Packet 类：`<Mod><Action>Packet`（如 `GoetyGuiSelectRitualPacket`）
- Module 类：`<Mod>RSModule`
- 探针类：`<Mod>Reflection`
- 私有辅助方法：`rsi$` 前缀（避免 Mixin 冲突）

### 6.2 错误处理

- **禁止静默 catch**：所有 `catch` 块必须保留异常对象引用，至少 `LOGGER.debug("msg", e)`。`catch (Exception ignored) {}` / `catch (Exception e) { LOGGER.warn("{}", e.toString()) }` 均为违规。
- **跨 mod 反射探测**（预期可能失败）：`RSIntegrationMod.LOGGER.debug("msg", e)` 或 `RSIntegrationMod.debug("msg", e)`（受 diagnostic 开关控制）
- **合成/提取失败**（用户操作相关）：`LOGGER.warn("msg", e)`
- **网络/绑定/账本错误**：`LOGGER.error("msg", e)`
- **Tick 路径**：使用 `warnOnce()` 限流，防刷屏
- **用户可见错误**：`player.sendSystemMessage(Component.translatable(...))`
- **网络包处理**：始终在 `context.enqueueWork(() -> {...})` 内操作世界

### 6.3 诊断日志开关

`RSIntegrationMod.debug(format, args...)` 是一个受 config 开关 `DIAGNOSTIC_VERBOSE_LOGGING` 控制的守卫日志方法。**高频 tick 路径中的 debug 必须用此方法**，避免默认关闭时产生无意义的字符串拼接开销。

一次性路径（启动初始化、注册）可直接使用 `RSIntegrationMod.LOGGER.debug(...)`。

开关状态缓存在 `RSIntegrationMod.verboseLogging` boolean 原语中，通过 `ModConfigEvent.Reloading` 监听配置重载，避免每 tick 调用 `ConfigValue.get()` 的同步开销。

### 6.4 不可变原则

| 原则 | 原因 |
|------|------|
| 不缓存 BlockEntity | 跨 tick 可能被卸载/替换 |
| 不缓存 IItemHandler | BE 可能重建 |
| 不缓存 Level / ServerLevel | 维度切换 |
| 修改 BE 后调 `setChanged()` | 否则重启/崩服回档 |

### 6.5 ItemStack 操作

- 用 `copy()` 创建副本，不直接修改网络中的物品
- `shrink` 掉落物后调 `entity.setItem(entity.getItem().copy())`
- 退物品前检查 `!player.hasDisconnected() && !player.isRemoved()`

---

## 七、事务安全（Ledger）

```
Phase 1: reserve → Phase 2: commit → Phase 3: craft
                                     ↑
                          commit 失败 → 已 reserve 归零，物品未动
                          commit 成功 → 物品已消耗，craft 必须完成或退款
```

- **共享账本**（`usingSharedLedger = true`）：退款由链式调度统一执行，单个 Delegate 不自行退款
- **独立账本**：`tryStartSingleCraft` 自行 create / commit / abort
- `abort()` 前检查 `network != null && network.canRun()`

---

## 八、常见陷阱

| 陷阱 | 后果 | 修复 |
|------|------|------|
| 跨 tick 缓存 BlockEntity | 刷物/吞物 | 每次 tick 重新 `level.getBlockEntity()` |
| `getBlockEntity()` 不先检查 `isLoaded()` | 触发区块加载 → TPS 掉帧 | 先 `level.isLoaded(pos)` |
| 共享账本下单方面退款 | 双倍刷物 | `onBatchFailed` 检查 `usingSharedLedger` |
| Tick 路径中 `warn`/`error` 无限流 | 故障机器每秒 20 条堆栈撑爆磁盘 | 使用基类 `warnOnce(state, fmt, args...)` |
| catch 块丢异常对象（`e.toString()` / 空体） | 故障完全不可追踪 | 始终 `LOGGER.xxx("msg", e)` |
| 修改 BE 不调 `setChanged()` | 重启/崩服回档 | 改完必须调 |
| `entity.getItem().shrink()` 不跟 `setItem` | 客户端不同步 | shrink 后调 `entity.setItem(entity.getItem().copy())` |
| 退物品不检查在线状态 | 物品掉虚空 | 检查 `!player.hasDisconnected()` |
| `configureJei` id 不一致 | JEI 按钮静默消失 | 两个 id 必须严格一致 |
| 网络包不在 `enqueueWork` 处理 | Netty 线程读写世界 → 崩溃 | 用 `context.enqueueWork(() -> {...})` |
| 机器坐标不校验绑定 | 全服隔空偷窃 | 服务端检查 `AltarBindingRegistry.isBound()` |
| 类名写死字符串而非探针 | 上游改名后运行时 NPE | 类名统一放探针，启动期校验 |
| `Field`/`Method` 存探针 | 违反探针设计原则 | 探针只存 `Class<?>`，字段/方法在调用点用 `Reflect` |
| Tick 路径中每帧读 `ConfigValue.get()` | Map 查找+同步开销累积 | 缓存 boolean 原语，监听 `ModConfigEvent.Reloading` |

---

## 九、JEI 按钮不显示 —— 排查顺序

1. `getBindingFilter` 返回 null？→ `configureJei` 的 UID/类名映射是否匹配
2. `findBinding` 没找到？→ filter 字符串是否与绑定注册时一致
3. Config 开关 false？→ 检查 `ENABLE_XXX`
4. 模块未注册？→ `RSIntegrationMod.MODULES` 列表
