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
├── api/                    # 对外接口（ISmithingRecipeAccessor, RSIMachineAccessor, VersionRange）
├── config/                 # 配置项（RSIntegrationConfig）
├── crafting/               # 合成调度核心
│   ├── batch/              #   IBatchDelegate 接口 + 链式调度
│   ├── plan/               #   合成计划生成
│   ├── loadbalancer/       #   负载均衡（ParallelCraftGroup + LoadBalancer）
│   ├── ExtractionLedger    #   事务账本（AutoCloseable）
│   └── CraftOutputInterceptor        # 世界掉落产物捕获（防磁铁/拾取抢走）
├── mixin/                  # Mixin 注入
│   ├── minecraft/          #   原版
│   ├── refinedstorage/     #   RS
│   ├── jei/                #   JEI
│   ├── crockpot/           #   Crock Pot 菜单
│   └── sophisticatedbackpacks/  # 背包升级自动化
├── mods/                   # 各模组集成代码（按 mod 分目录）
│   ├── goety/              #   Delegate + Packet + 辅助类
│   ├── malum/
│   └── ...
├── network/                # 网络包定义与注册
├── recipe/                 # ModRecipeHandler 配方适配层
├── reflection/             # 反射系统
│   ├── probes/             #   18 个 XXReflection 探针（Class<?> 集中管理）
│   └── contract/           #   ReflectionContract + ContractValidation（启动期校验）
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
    ModType.delegateSupplier("com.huanghuang.rsintegration.mods.malum.MalumBatchDelegate"));
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

`IBatchDelegate` 是机器交互的核心接口，所有实现继承自 `AbstractBatchDelegate`（`crafting/batch/AbstractBatchDelegate.java`）。

**模板方法（`final`，基类统一实现，子类禁止覆写）：**

| 模板方法 | 内置保护 |
|----------|----------|
| `isCraftComplete(level)` | `level.isLoaded(pos)` 区块卸载保护；`be == null \| be.isRemoved()` → 视为完成 → 委托钩子 |
| `onBatchFailed(player, reason)` | `usingSharedLedger` 防双倍退款 + 区块加载保护 → 委托钩子 |

**钩子方法（子类覆写，业务逻辑在此）：**

| 钩子 | 职责 |
|------|------|
| `validateAndInit(player, recipeId, dim, pos)` | 验证机器存在、空闲、配方可执行 |
| `getRequiredMaterials()` | 返回按机器放置顺序排列的全部材料 |
| `getGraphSpecs()` / `getSupplementalSpecs()` | 默认全部材料由 graph 分配；仅当配方规划层有意隐藏催化剂时拆分，supplemental 由执行层直接预留 |
| `mergeSupplementalMaterials()` | 将 graph 材料与 supplemental 材料恢复为机器要求的准确放置顺序；非简单尾部追加时必须覆写 |
| `concurrencyCapabilities()` | 声明跨 DAG 节点并发所需的材料、输出、cleanup、副作用和机器 scope 契约；未知或不完整时保持 exclusive |
| `getMachinePos()` | 返回绑定的机器坐标（`abstract`，每个子类必须实现） |
| `tryStartSingleCraft(player)` | 独立路径：从网络提取材料 → 填入机器 → 触发合成 |
| `tryStartSingleCraft(player, sharedLedger)` | 链式路径重载：材料已由链调度预扣（`default` 方法，按需覆写） |
| `tryStartWithMaterials(player, materials, sharedLedger)` | 链式路径：材料已预扣，直接填机器 |
| `isMachineCraftFinished(level, be)` | tick 轮询：合成是否完成（`level` + `be` 已由模板保证非空且区块已加载） |
| `collectResult(player)` | 提取产物，返回 `ItemStack` |
| `getExpectedOutput()` / `getOutputCaptureRegion()` | 世界掉落型机器声明预期产物及生成范围，供 `CraftOutputInterceptor` 抢在磁铁前捕获 |
| `clearMachineState(be, player)` | 清退机器内残留材料 |
| `onBatchFinished(player)` | 批次结束清理（覆写时须调用 `resetState()`） |
| `resetState()` | 重置 ledger / network / sharedLedger / `usingSharedLedger` / `seenWarnStates`（基类提供，`onBatchFinished` 和 `clearMachineState` 末尾必须调用） |

**Delegate 不应缓存 BlockEntity / IItemHandler**——每次 tick 重新 `level.getBlockEntity(pos)` 获取。

**`warnOnce(state, format, args...)`** — tick 路径限流日志（`HashSet<String>` 去重），每个不同的 `state` key 在批次生命周期内最多输出一次（A→B→A 不会重复输出 A）。`resetState()` 中清空。

### 3.4 绑定系统（Binding）

玩家手持 RS 网络终端右键机器方块，建立"RS 控制器 → 机器"绑定关系。服务端通过 `AltarBindingRegistry` 存储，`RSAvailabilityChecker` 在提交合成前校验绑定有效性。

### 3.5 机器三类分法

| 类型 | 特征 | supportsGui | 示例 |
|------|------|-------------|------|
| **GUI 容器** | 右键打开 GUI，有 Menu/Screen | `true` | Crock Pot, Smithing Table |
| **世界交互（tick 型）** | 丢/放物品到方块，有进度条 | `false` | Spirit Altar, NecroBrazier |
| **世界交互（即时型）** | 手持物品右键即完成，无进度 | `false` | Runic Workbench |

即时型必须在 `MachineInteractType.fromBlockKey()` 中添加关键词，否则会错误尝试打开 GUI。

### 3.6 物理产物收取

- 世界 `ItemEntity` 产物由 Delegate 通过 `getExpectedOutput()` 和 `getOutputCaptureRegion()` 声明，交给 `CraftOutputInterceptor` 在实体加入世界时捕获。
- 机器槽位产物允许漏斗、管道等外部自动化提取；`collectResult()` 只能收取调用时真实存在于输出槽中的内容，槽位为空时不得用预期配方结果补发。
- 异步 Delegate 必须通过 `observeCraft()` 明确报告 `WAITING_FOR_START → WORKING → DONE`；机器消失报告失败，不能当作成功。只有进入 `WORKING` 后，输入消耗或真实输出出现才能进入 `DONE`。
- 可计数的物品输出实现 `getExpectedProduction()`；链会汇总 `collectAllResults()` 的真实数量。少于预期表示部分外流：当前步骤输入不退，但更早步骤仍持有的中间产物会恢复。
- 同一机器可能产生多个物品栈、crafting remainder 或可容器化流体时，必须覆写 `collectAllResults()` 并返回全部实际移出的栈；若这些物理副产物已包含在结果中，应禁止链再按 recipe metadata 补发。
- 流体转物品必须先验证容器转换结果并执行 `SIMULATE` drain，确认可精确排出后才 `EXECUTE`；转换失败时流体保留在 tank 中。

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

`mods/<mod>/XXBatchDelegate.java`，继承 `AbstractBatchDelegate`，覆写钩子方法（`isMachineCraftFinished`、`clearMachineState` 等）。

产物保护必须在实现阶段一并确认：

- 结果生成 `ItemEntity`：实现 `getExpectedOutput()` 和精确的 `getOutputCaptureRegion()`；`collectResult()` 找不到真实实体时返回 `EMPTY`，禁止返回 recipe template。
- 结果写入机器槽：只收取调用时真实存在于输出槽中的内容；允许外部自动化提取，槽位为空时返回 `EMPTY`。
- 结果来自缓存、直接 assemble、流体 tank 或虚拟计算：不要错误声明世界捕获或物品输出槽。
- 若机器有动态输出位置/槽位，必须返回真实位置（例如基座/下方容器），不能只返回主机代表坐标。

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
- Packet 类：`<Mod><Action>Packet`（如 `MalumCraftPacket`）
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

`ExtractionLedger` 实现 `AutoCloseable`，**所有新创建的 ledger 必须用 try-with-resources 包裹或在 finally 中显式 `close()`**。

### 状态机

```
IDLE → RESERVING → RESERVED → COMMITTING → COMMITTED
                           ↓                  ↓
                       ROLLED_BACK      (物理物品已移动)
```

- `close()` 是安全操作：`COMMITTED` / `ROLLED_BACK` 状态下为 no-op；否则清除内存预留（物品未被物理移动）
- `rollback(player)` 对 `COMMITTED` 状态调用 `refundCommitted()` 退还物理物品；对 `RESERVING` 状态清除预留
- `commit()` 成功 → `COMMITTED`；失败 → `ROLLED_BACK`

### 三类使用模式

| 类别 | 生命周期 | 模式 |
|------|---------|------|
| **A — 字段级** | 对象存续期间 | `ledger.close()` 放在 `resetState()` / `abort()` / `abortSilently()` 中 |
| **B — 方法局部** | 方法内 | `try (ExtractionLedger ledger = new ExtractionLedger()) { ... }` |
| **C — 条件共享** | 可能共享或自建 | `boolean ownsLedger = !usingSharedLedger \|\| sharedLedger == null;` + `finally { if (ownsLedger) localLedger.close(); }` |

### 关键规则

- **共享账本**（`usingSharedLedger = true`）：退款由链式调度统一执行，单个 Delegate 不自行退款（`onBatchFailed` 中 `if (usingSharedLedger) return;`）
- **独立账本**：`tryStartSingleCraft` 自行 create / commit / abort
- `abort()` 前检查 `network != null && network.canRun()`
- `commit()` 后失败需要手动 `ledger.rollback(player)` — `close()` 对 `COMMITTED` 是 no-op，不退还物理物品

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
| `new ExtractionLedger()` 不用 try-with-resources | 异常路径物品泄漏 | 三类模式（字段级 `close()` / 方法局部 try / 条件共享 `ownsLedger`），见第七节 |
| 覆写 `isCraftComplete` / `onBatchFailed` 而非钩子 | 丢失区块保护 / 双倍退款 | 只覆写 `isMachineCraftFinished` / `clearMachineState`，模板方法是 `final` |
| `commit()` 后用 `close()` 而非 `rollback(player)` | 物品静默消失 | `close()` 对 `COMMITTED` 是 no-op；必须显式 `rollback(player)` 才能退还物理物品 |
| 配方产物是"放置型方块物品"却整块收走 | 玩家的锅/容器被吞进网络（如妖怪归家汤锅） | 判断产物 `BlockItem` 是否为可盛装方块（如 `PotFoodBlock`），盛成其可消耗形态（`asBowls()` → 食物×份数）并把碗计入必需材料，而非收走整块 |
| 同一机器的 Delegate 与 GUI 预填各写一套资源选择策略 | 自动合成与远程 GUI 的燃料/材料选择不一致 | 抽取无网络依赖的共享策略（如原版炉子的 `VanillaFurnaceFuelPolicy`），两个入口只保留提取与写槽动作 |
| 世界掉落产物缺失时直接返回 recipe result | 磁铁/玩家已拿到真实产物，RS 又收到模板产物，形成复制 | 世界掉落型实现捕获声明；交付只认捕获或实际取走的实体/槽位，缺失时 fail closed |
| 只把 `supportsConcurrentNodeExecution()` 改成 `true` | 未声明材料、输出、cleanup 或副作用所有权的机器被错误并发，导致串料/刷物 | 实现完整 `BatchConcurrencyCapabilities`；混合 delegate 再按实际 recipe subtype 走 `GraphConcurrencyEligibility` |
| queued operation 先 claim 再拿机器/capture lease | 资源冲突时队列项持有半套状态并可能永久卡住 | 先原子获取 budget + machine/support + capture 完整 scope，再 claim operation |
| 仪式只锁主方块 | 两个邻近祭坛共享 pedestal/邻接资源并同时写入 | 将所有 support positions 纳入原子 machine scope；范围不确定时保持 exclusive |
| infer group child 创建 normal delegate | 每个 worker 执行错误算法或共享错误状态 | 透传 `inferMode`，每个 worker 调 `createInferDelegate()` |
| 槽位产物被外部自动化提前提取 | `collectResult()` 时槽位为空 | 允许外部提取；只收真实槽内容，禁止用预期配方结果补发 |

---

## 九、JEI 按钮不显示 —— 排查顺序

1. `getBindingFilter` 返回 null？→ `configureJei` 的 UID/类名映射是否匹配
2. `findBinding` 没找到？→ filter 字符串是否与绑定注册时一致
3. Config 开关 false？→ 检查 `ENABLE_XXX`
4. 模块未注册？→ `RSIntegrationMod.MODULES` 列表

---

## 十、共振磁盘被动效果系统

### 10.1 三层架构

被动效果系统让 RS 共振磁盘中的物品像在玩家背包里一样生效，分为三层：

| 层 | 机制 | 覆盖率 | 配置 |
|---|---|---|---|
| Phase 1 | 属性修饰符 (AttributeScanner) | ~50-70% | 零配置 |
| Phase 2 | inventoryTick 模拟 (TickSimulator) | ~20-35% | `passiveTickItems` JSON 白名单 |
| Phase 3 | 事件驱动 Mixin 重定向 | ~5-15% | 每个物品一个 Mixin |

**Phase 1 — AttributeScanner**：扫描物品的 `AttributeModifier`，自动应用到玩家。绝大多数"放在背包里加属性"的物品走这一层。

**Phase 2 — TickSimulator**：模拟调用物品的 `inventoryTick`。`|mutates` 标记的物品走 extract→tick→insert 以持久化 NBT 变更；无标记的物品在快照副本上 tick（只读）。

**Phase 3 — Event-driven Mixins**：特定物品需要劫持原版事件处理器，因为原版代码通过 `hasItem()` / `instanceof` 检查而非属性修饰符。当前覆盖：

| Mixin | 目标 | 策略 |
|---|---|---|
| `SuperpositionHandlerMixin` | `SuperpositionHandler.hasItem()` | RETURN 注入补查磁盘 |
| `AddonEventHandlerMixin` | `AddonEventHandler` 中的 `hasItem` 调用 | `@Redirect` 重定向含磁盘查找 |
| `NineSwordBooksMixin` | Moonstone `nine_sword_books` | `@Redirect` `List.size()` + ThreadLocal |
| `YuushaNineSwordBooksMixin` | Yuusha compat `NineSwordBooks` | `@Inject RETURN` `countValidSwords` + `calculateTotalSwordDamage` |
| `AvariceRingMixin` | `AvariceRing` 背包遍历 | 遍历重定向到磁盘 |
| `PyromancerStaffMixin` | `PyromancerStaff` 消耗 | 消耗重定向到磁盘 |

### 10.2 Phase 3 Mixin 写作模式

向已有模组事件处理器添加磁盘感知的两种模式：

**模式 A — 通用 hasItem 拦截**（适用多数物品）：

在 `SuperpositionHandler.hasItem()` RETURN 处注入，原版返回 false 时补查磁盘。无需修改 call site。所有调用者自动受益。

**模式 B — 特定 call site 重定向**（仅当模式 A 不够时使用）：

当调用者在到达 `hasItem()` 前因前置条件失败退出时，用 `@Redirect` 劫持前置条件检查本身（如 `betrayalAvailable`），补充磁盘感知。需反编译目标类的字节码以确定精确注入点。

### 10.3 调试 hasItem 问题

Mixin 日志前缀：`[RSI-hasItem]`、`[RSI-NineSwords]`、`[RSI-YuushaNineSwords]`。

排查步骤：
1. 检查 `SuperpositionHandlerMixin.rsi$logCall` 日志确认该物品是否被查询过
2. 若从未查询 → 前置条件在 `hasItem` 之前就失败了，需反编译 Caller 找阻塞点
3. 若查询了但没找到 → 检查磁盘是否真的包含该物品，以及 `stack.is(item)` 是否匹配

### 10.4 核心类

```
src/main/java/com/huanghuang/rsintegration/resonance/
├── disk/
│   ├── ResonanceDiskWrapper      # IStorageDisk 装饰器，重导出磁盘只读视图
│   ├── ResonanceDiskFactory       # RS 磁盘注册表 SPI
│   └── ResonanceDiskItem          # 磁盘物品
├── bridge/
│   └── RSInventoryBridge          # 共享工具：getResonanceDisk() / insertToDisk()
├── passive/
│   ├── PassiveEffectEngine        # 主调度器（PlayerTick + 缓存 + 三层编排）
│   ├── PassiveRegistry            # Phase 2 白名单注册表
│   ├── TickSimulator              # inventoryTick 模拟（snapshot / mutate 模式）
│   └── AttributeScanner           # Phase 1 属性扫描器
└── backpack/
    └── ResonanceDiskInventory      # 共振背包 GUI 容器
```

### 10.5 新增被动效果集成流程

1. **判定层级**：先确认物品是否已通过 Phase 1 或 Phase 2 支持——大部分是。
2. **若需 Phase 3**：反编译物品相关的事件处理器，找到 `hasItem()` 或等效的背包查验点。
3. **首选模式 A**：在 `SuperpositionHandlerMixin` 层面解决问题（泛用，低侵入）。
4. **必要时模式 B**：针对特定 call site 写独立的 `@Redirect` / `@Inject`。
5. **ThreadLocal 注意**：若需在多个方法间传递信息（如 HEAD→ModifyVariable），用 `@Unique ThreadLocal`，并在 RETURN 时 `remove()` 防止泄漏。
6. **诊断日志**：前 5 次触发用计数器和 `LOGGER.info` 输出诊断信息，确认 Mixin 正确命中。

---

## 十一、DAG 合成规划与执行

当前自动合成同时包含 legacy `PlanStep` 视图和图驱动路径。DAG 是服务端权威的物料流模型，客户端只渲染 `PlanGraphView`，分支选择仍通过服务端重新解析完成。

### 11.1 核心模型

| 模型 | 职责 | 关键约束 |
|------|------|----------|
| `CraftPlanGraph` | 节点、输入端口、输出端口、物料分配和 root demand | 必须通过 `CraftPlanValidator` |
| `CraftNode` | 单个配方执行节点 | `executions`、alternatives、inputs、outputs 必须自洽 |
| `MaterialBroker` | 节点间物料预留、提交、结算和退款 | 不允许绕过 source/material/quantity 进行隐式扣料 |
| `DagScheduler` / `NodeAdmissionCoordinator` | DAG 调度、能力门控和并发准入 | dispatch 前必须完成无副作用能力探针 |
| `PlanGraphView` | 发给客户端的只读 DTO | 不携带 Ingredient predicate，只传显示栈、端口和数量 |

### 11.2 物料守恒规则

- `InputDemand.quantity` 是该节点实际需要的物料数量；`OutputDeclaration.quantity` 是该输出端口在所有 `executions` 完成后发布的总数量。
- `MaterialAllocation.quantity` 必须同时满足消费者输入端口和生产者输出端口的容量，不能超配。
- root demand 必须满足：`allocations 总量 + unresolvedQuantity = quantity`。
- `PlanTreeNode.amount` 表示当前树引用的需求/边分配量，不能改成配方产出量；多产出配方的单次产量属于独立的显示信息。
- `abort` 后，已提交的物理物品只能由链级看门狗/显式退款流程处理；不要依赖 `ExtractionLedger.close()` 退还 `COMMITTED` 物品。
- DAG 中的同一生产节点可以被多个消费者引用；不能因为树上出现多个视图节点就重复执行或重复统计生产量。

### 11.3 解析器与候选配方

- 直接库存已有材料时，必须先尝试无副作用的直接消耗，再执行超时/次数预算检查；否则复杂计划后面的材料会被误报为缺失。
- `COMBINATION_OR` 等 OR 需求树必须先展开为 DNF，再按每个可行组合解析；禁止将 OR 分支 flatten 成 AND。
- 候选配方的 alternatives 必须从 `CraftNode` 传到 `PlanGraphView.NodeView`、`PlanResponsePacket` 和 `PlanStep`，否则 DAG 配方树不会显示切换控件。
- 产出数量必须来自真实 recipe output stack。不要在 handler、graph output 或树 DTO 的转换阶段提前 `copyWithCount(1)`；只有显示模板/物料键需要单位栈时才单位化。
- NBT 敏感产物必须使用 `ItemStack.isSameItemSameTags` 判断候选是否匹配，不能只按 `Item` 合并。

### 11.4 Delegate 输出契约

- `collectResult()` 只返回调用时真实提取到的产物；不能在槽位为空或世界掉落丢失时用 recipe template 补发。
- 多产物配方必须实现 `collectAllResults()`，包含主产物、副产物、remainder 和可容器化流体转换后的真实物品。
- `tryGetSecondaryOutputs` 的字段扫描必须限制在明确的副产物字段，不能把主产物字段再次计入。
- 产物提取要使用明确的 handler/delegate 契约；不要用“扫描第一个 `ItemStack` 字段”猜测产物。
- Malum、Ferment、TLM、Malum Spirit Crucible 等特殊配方必须用 fixture 或集成环境确认输出字段和多产物行为。

### 11.5 网络与客户端视图

- 修改 `PlanResponsePacket`、graph DTO 或任何已有包的 wire layout 时，必须递增 `NetworkHandler.PROTOCOL_VERSION`，并同步更新 encode/decode 和 bounds 测试。
- 网络包处理世界状态必须在 `context.enqueueWork(...)` 内完成。
- graph DTO 的集合使用现有 bounded count；字符串使用明确的最大长度，拒绝截断/恶意大分配。
- 客户端不能把 `PlanGraphView` 当成执行权威；用户切换候选后必须让服务端重新解析并返回新计划。
- DAG 和 legacy 视图的数量语义必须一致：需求量用于材料统计，recipe output count 用于产出展示。

### 11.6 跨节点并发与资源所有权

DAG 并发采用 fail-closed capability 模型。`supportsConcurrentNodeExecution()` 仅为兼容旧实现保留，单独返回 `true` 不会获得并发资格。中央决策由 `GraphConcurrencyPolicy` 和 `GraphConcurrencyEligibility` 完成，并同时考虑 `ModType`、实际 recipe 类和 `inferMode`。

`BatchConcurrencyCapabilities` 必须完整声明：

- `materials = CHAIN_RESERVED`：所有 recipe 材料来自链级预留，delegate 不得在启动时再次从 RS 自提取；
- `outputOwnership`：只能是明确的机器槽、delegate result 或 `OWNED_WORLD_CAPTURE`；entity/ambiguous 默认拒绝；
- `cleanup = SEPARABLE_OFFLINE`：cancel、failure、玩家离线和 server stop 都能清理物理状态，且不与 shared ledger 重复退款；
- `sideEffects`：机器局部、已纳入 support scope 的邻接机器或明确的 infer；全局世界状态、玩家变换和未知副作用保持 exclusive；
- `preparation`：READY/RETRY/FATAL 分类可信；
- `supportOffsets`：祭坛、基座、pedestal 等邻接资源必须纳入原子 machine scope。

资源获取顺序固定为：operation budget → 完整 machine/support scope → capture lease → commit → start。任何冲突都整体 `RETRY`，不得让 queued operation 持有部分 lease。`ParallelCraftGroup` 必须先获得完整 scope，再 claim operation ID；capability-parallel delegate 不允许退回 `armCaptureLegacy`。

当前 recipe-aware allowlist：

| 类别 | 已允许 | 仍明确排除 |
|------|--------|------------|
| Malum | Spirit Altar、Spirit Crucible、Runic Workbench | 未知 fallback |
| Goety | Dark Altar ritual、Necro Brazier | delegate 验证拒绝的 convert/teleport 等 ritual |
| Forbidden & Arcanus | Hephaestus Forge ritual | `ApplyModifierRecipe` |
| TLM | Altar | 未知 subtype |
| YH | Steamer、small/short/large Cooking Pot；另有 Fermentation Tank 机器槽并发 | Moka、Kettle、Cuisine、fallback |
| Farmer's Delight | Skillet/campfire binding | Cooking Pot、fallback |
| Vanilla | 仅 `vanilla_campfire` | furnace、blast furnace、smoker、stonecutter、anvil、smithing 等 |
| Aether | Altar | Freezer、Incubator、fallback |
| Eidolon | `ItemRitualRecipe`、`GenericRitualRecipe`、Crucible/Brazier subtype | `WorktableRecipe` 和未知 subtype |
| Wizards Reborn | 仅 `CrystalRitualRecipe` | `CrystalInfusionRecipe`、`ArcaneIteratorRecipe`、Crystallizer、Workbench |
| Embers | 普通 alchemy tablet；infer 仅 `EreAlchemyInferDelegate`；aspect 催化剂通过 supplemental reservation 从 RS 网络补充，并按 `tablet + (aspect, input) × N` 放置 | 其他 infer/fallback |
| 机器槽/明确结果 | Avaritia Crafting Table、Farmer's Respite Kettle | capability 未完成的 Compressor、Cooler、Crab Trap 等 |

world-output delegate 只有声明 `OWNED_WORLD_CAPTURE` 才能创建 capture request。`CaptureLeaseRegistry` 按维度、区域和输出 material 防止冲突；缺少预期输出或 capture 获取失败时必须 fail closed，不能补发 recipe template。

仪式/祭坛目前将主机器周围水平 3 格纳入 support scope。新增机器若真实结构更大或包含垂直 support，应声明精确 offsets，不能依赖该默认范围掩盖资源所有权缺口。

Embers infer 的每个并行 worker 必须通过 `ModType.createInferDelegate()` 创建独立实例，并由 `ParallelCraftGroup` 透传 `inferMode`；禁止使用 normal delegate 替代。

配置项：

- `craftingMaxConcurrentGraphNodes`：独立 DAG 节点总并发上限，默认 `1`；
- `craftingMaxConcurrentOperations`：物理 operation 总并发上限；
- `craftingParallelDisabledMods`：mod/type 强制 exclusive denylist；
- `craftingParallelDelegatePolicies`：`id=AUTO|OFF|FORCE_WITH_GUARDS`，delegate 类名比 mod type ID 更具体；`FORCE_WITH_GUARDS` 不能绕过安全 guard。

并发日志必须包含 `nodeId`、`modType`、delegate、parallel/exclusive 决策及具体 reason。人工验收时至少将 `craftingMaxConcurrentGraphNodes` 调为 `2`，验证两台不同机器同时 RUNNING、同一机器或重叠 capture 保持 RETRY，并确认 cancel/failure/server stop 后 machine、support、capture 和 budget 全部清零。

### 11.7 新增或修改 DAG 功能的验收清单

- [ ] `CraftPlanValidator` 覆盖输入、输出、root、unresolved 和拓扑顺序。
- [ ] 同一生产节点被多个消费者引用时，分配总量和执行次数仍正确。
- [ ] 多产出、remainder、NBT 产物均有独立测试。
- [ ] alternatives 完整经过 `CraftNode → PlanGraphView → packet → client PlanStep`。
- [ ] `PlanTreeNode.amount` 未被改写为产出数量。
- [ ] 协议变更已更新版本号、编码、解码和边界测试。
- [ ] 直接材料、超时预算、OR 配方和 abort 路径各有回归测试。
- [ ] capability 完整/缺失、recipe subtype allow/deny、同 key 冲突、support scope 原子回滚和 capture 冲突均有测试。
- [ ] `craftingMaxConcurrentGraphNodes = 2` 时不同机器可同时 RUNNING；同机器、重叠 capture、failure/cancel/terminal 后资源状态符合预期。
- [ ] 目标配方在实际整合包中验证：配方树数量、候选切换、材料总需求和实际收取结果一致。

---

## 十二、测试与发布前检查

### 12.1 推荐测试命令

```bash
# 编译主代码
./gradlew compileJava

# 运行单个逻辑测试类
./gradlew test --tests "*CraftPlanValidatorTest"
./gradlew test --tests "*MaterialBrokerTest"
./gradlew test --tests "*PlanGraphViewTest"
./gradlew test --tests "*PlanTreeModelGraphTest"

# 完整测试
./gradlew test
```

Windows 环境使用 `gradlew.bat`。若 Gradle 报告无法删除 `build/test-results`，先停止占用测试输出的 Gradle daemon/IDE 测试进程，再重新运行；不要把失败测试结果误判为代码测试通过。

### 12.2 发布前最小验收

- [ ] `compileJava` 成功，且没有新增编译错误。
- [ ] 关键 ledger、resolver、graph、packet 测试通过。
- [ ] 至少用一个真实整合模组配方完成从计划生成到产物回收的闭环。
- [ ] 验证机器卸载、玩家断线、服务器重启/重载、外部管道提前取走产物等失败路径。
- [ ] 检查中文和英文语言键同步，网络协议版本与客户端/服务端构建一致。
- [ ] 检查 `git diff`，确认未误改用户已有工作区变更，并在提交前记录未能运行的测试及原因。
