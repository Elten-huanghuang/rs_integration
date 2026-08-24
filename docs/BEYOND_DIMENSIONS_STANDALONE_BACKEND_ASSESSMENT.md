# BeyondDimensions 独立存储后端可行性评估报告

> **2026-08-23 当前实施批次：BD 执行链后端化审计与修正（最新）**
>
> 本批先完成了“调用链是否真的使用所选后端”的审计和修正，没有把“能读取 BD 快照”误判为“BD 已完整可用”。当前变更包括：
>
> - `AsyncCraftChain` 的图节点、原版节点、机器节点、并行组、私有账本、退款、断线恢复、虚拟中间产物和最终产物回存统一经 `CraftStorageEndpoint`；RS 的 `INetwork` 只保留在兼容桥和 RS 状态门禁中。
> - `GenericCraftPacket` 的所有递归/图异步启动分支都传递 endpoint；异步完成后的重复下单会保留 `StorageReference`，不会回退到默认 RS 网络。
> - `ExtractionLedger.reserveFromEndpoint` 改为按 Ingredient 汇总多个 BD 具体物品变体，再由提交阶段执行匹配提取，避免“每个 BD 条目必须单独满足全部数量”的错误。
> - 配方树输出选择器改为：左键在玩家背包与当前存储网络间切换，右键循环 RS/BD 的具体网络；核心递归文案改为“存储网络”，不再提示必须打开 RS 终端。
> - BD 便携网络终端已接入现有机器绑定链：手持 `beyonddimensions:net_terminal_item` 使用可配置的 Alt+右键绑定/解绑机器；BD 终端原生的潜行右键网络绑定仍由 BD 自己处理，不会被 RSI 机器绑定入口重复消费。绑定类型携带 BD network ID，机器委托、配方树和绑定提示复用公共实现。
> - 显式绑定包增加同一玩家/位置/手的短窗口去重，覆盖客户端按下/抬起重复投递导致的“绑定后立即解绑”；空计划错误不再打开目标为“空气”的配方树，而是直接显示失败原因。
> - 修正 BD 默认网络解析：`getPrimaryNetFromPlayer` 不可用、未授权或指向其他网络时，会回退到已发现且已授权的网络（包括 `net_terminal_item` 绑定的 network ID），并将该引用写入计划，而不是只记录发现结果后继续报“未找到存储网络”。
> - 进一步修正计划入口：即使后端默认解析返回空，只要发现列表中存在可解析的 BD 网络，也会把该网络提升为当前计划 endpoint；这覆盖 BD 玩家有成员资格但尚未设置 primary network 的情况。
> - 计划请求遇到失效的旧 `StorageReference` 时不再立即报无网络，而是重新解析当前玩家的默认/发现网络；同时记录请求中的 backend/id 和恢复后的目标，便于区分 stale 引用与 BD 授权失败。
> - 修正机器配方预览的旧 RS 门禁：BD endpoint 已成功解析时，不能因为兼容字段 `INetwork == null` 就把 typed resolver 判定为“无网络”；现在以统一 `CraftStorageEndpoint` 作为可用性依据。
> - 中英文补齐了负载均衡、砖炉、Eidolon、Apotheosis 等实际缺失键，以及账本错误键；RS 专属模块的文案仍保留为 RS 专属，不伪装成 BD 支持。
>
> 本批 `compileJava` 和 `jar` 已通过。新增的双网络修正会同时发现 BD 玩家成员网络和背包中绑定终端指向的网络，并在日志输出 BD 发现的 network ID。**当前仍不能宣称 BD 已完成验收**：还缺 BD-only 实际启动、BD 网络真实存取、机器投料/回收、退款、配方树下单和无 RS 客户端/专服冒烟。下一次停点应是用最新 JAR 做一轮 BD-only 和 RS+BD 双装测试，而不是继续扩展基础接口。


> **2026-08-23 接口复核与实施状态（本节为当前结论）**
>
> 已按 `D:\sd\BeyondDimensions` 的 `1.20.1` 源码重新核对公开 API：
> `DimensionsNet.getPrimaryNetFromPlayer(Player)`、`getAllNetFromPlayer(Player)`、
> `getNetFromId(int)`、`getId()`、`getPlayers()` 和 `getUnifiedStorage()` 均实际存在；
> `UnifiedStorage` 继承 `IStackHandler`，提供 `getStorage()`、
> `insert(IStackKey,long,boolean)`、`extract(IStackKey,long,boolean,boolean)`；
> `ItemStackKey(ItemStack)`、`getReadOnlyStack()` 和 `KeyAmount.key()/amount()` 也已确认。
> BD 的网络 ID 是服务端持久化整数，成员集合可用于 RSI 的 VIEW/EXTRACT/INSERT 基础授权映射。
>
> 第一批代码已经开始：新增 `storage/bd` 反射隔离适配器、BD provider/session，
> 并在 `RSIntegrationMod` common setup 中通过可选 descriptor 尝试注册 BD。BD 未安装时只返回
> `MOD_NOT_LOADED`，不会加载 BD 类；当前 `compileJava` 已通过。此批只完成后端入口、网络解析、
> 成员校验、快照、精确/Ingredient 提取和插入余量规范化，**尚未迁移业务入口，也没有改为无 RS
> 发布形态**。
>
> 当前实现暂用反射，因为 BD 工作树没有可直接复用的发布 API JAR，且其 Gradle 构建链尚未稳定产出
> 发布物。拿到正式 BD JAR 后，应将反射边界替换为 `compileOnly` 强类型 driver，并保留同一
> `StorageBackend` 契约；这预计是 1 个适配器文件级别的替换，不应影响核心业务层。
>
> **工作量更新**：后端骨架约 250 行；强类型 driver、测试夹具和真实网络验收还需约 150-250 行。
> 之后的可选依赖/RS 专属类加载隔离约 20-35 个文件，约 400-800 行；机器委托、递归合成、
> 磁铁、拾取/喂食/补货和 UI 入口不再逐个重写存储逻辑，预计主要是 endpoint 选择和绑定数据迁移。
> RS+BD 同装时仍必须增加显式后端选择，不能默认把两个网络合并。

> **本轮代码审计修正**：复核新增 `storage/bd` 后确认并修复了以下问题：
> Ingredient 提取中间步骤失败会正确向上传播；正式插入/提取在 native 调用后发生异常时返回
> `INDETERMINATE`；BD 返回的 KeyAmount 会校验数量范围和物品身份，避免把错误响应当成成功；
> 大数量会受 Minecraft `ItemStack` 整数堆叠限制保护；网络删除、替换或失效后旧 session 不再继续操作；
> 权限检查现在区分 `DENIED`、`UNAVAILABLE` 与 `FAILED`。同时删除了重复 backend ID 常量、无效导入和
> 未使用反射常量。当前仍保留一个有意的边界：反射层是临时兼容方案，获得 BD 正式 API JAR 后应替换为
> 强类型 driver，但不应再复制一套新的业务存取逻辑。

> **扩展审计：用户界面、翻译和配方树仍有遗漏（当前必须纳入 P0）**：
>
> 1. **配方树网络来源尚未后端化**。`GenericCraftPacket.tryBuildPlan` 只在 RS 已加载时调用
>    `CraftPacketUtils.resolveNetworkForCraft`，否则把材料来源降为玩家背包；它没有调用 BD
>    `StorageBackendRegistry`。因此 BD-only 配方树目前只能“看配方/看背包”，不能从 BD 网络生成完整材料快照。
> 2. **配方树下单没有保存网络引用**。`OutputDestination` 只有 `RS_NETWORK` 和
>    `PLAYER_INVENTORY`；`GenericCraftPacket` 的默认值、编码、解码和服务端执行仍以 RS 网络为目标。
>    `CraftingPlanScreen` 也只有“RS 网络/玩家背包”二段切换，`CraftingPlanPreferences` 只保存这个旧枚举。
>    RS+BD 同装时没有选择 `backendId + networkId` 的入口，`CraftStorageEndpoints.resolveDefault()`
>    还会按注册顺序取第一个后端，实际等价于 RS 优先猜测。
> 3. **执行链仍存在 RS 原生参数**。`ResolutionContext`、`AsyncCraftChain`、
>    `GenericBatchDelegate`、`AbstractBatchDelegate` 和多个机器 delegate 仍保存或接收 `INetwork`；
>    `CraftStorageEndpoints` 的生产调用点大量通过 `fromLegacyNetwork` 进入 `LegacyRsCraftStorageEndpoint`。
>    这意味着 BD session 虽然能单独做快照/存取，但还没有进入递归中间步骤、机器委托、退款和产物回收的真实调用链。
> 4. **无 RS 下单前置判断仍写死 RS**。`GenericCraftPacket.handle` 使用
>    `ModList.isLoaded("refinedstorage")` 判断网络目标；BD-only 且目标为网络时会在执行前直接拒绝。
>    该判断应改为“是否有已选且可解析的后端引用”，不能只改成“是否安装任一存储模组”。
> 5. **翻译键虽然中英文数量一致（各 944 个），语义仍是 RS 专属**。例如
>    `rsi.plan.output.rs`、`rsi.generic.error.network_unavailable`、
>    `rsi.ftb_quest.error.no_network`、`rsi.transfer.*`、`rsi.side_panel.*`、
>    `rsi.enchanting.restock.*`、`rsi.villager.restock.*`、`rsi.autoeat.*` 和多个机器燃料/回收提示
>    都直接显示“RS 网络”。需要新增通用“存储网络/当前后端/网络不可用/无提取权限”键，并以动态
>    后端名或网络名渲染；不能靠把 `RS` 文本批量替换成 `BD`，因为 RS-only、BD-only、双装三种上下文不同。
>    本轮同时补齐了三个实际缺失的静态键：`rsi.generic.error.machine_valid_failed`、
>    `rsi.generic.error.not_bound`、`rsi.goety.error.ritual_start_failed`；剩余问题属于后端化文案，
>    不能通过简单补 key 解决。
> 6. **背包升级和机器绑定仍是 RS 格式**。`RSMagnetUpgradeItem` 只识别 `RSBlockPos`/
>    `RSBlockDimension` 和 `ControllerBlockEntity`；`StorageBackpackUtils` 固定构造
>    `refinedstorage` 坐标引用；`AltarBinding`/`BindingEventHandler` 只注册 `RS_NETWORK`。
>    BD 需要自己的 network ID 绑定格式，并且双装时绑定物品必须携带后端 ID，不能复用 RS 坐标 NBT。
> 7. **入口和 UI 仍是 RS 专属**。`RSJeiPlugin` 的 GUI handler 只注册 RS Grid；侧栏、传输模式、
>    一键吃和机器管理中心的服务端同步/来源提示仍使用 `RSSidePanel*`、RS 终端上下文或 RS 词汇。
>    BD 需要独立终端入口或通用 RSI 入口，且来源网络应随 `StorageReference` 传递。
> 8. **明确的 RS-only 功能不应伪装成 BD 支持**。共振盘、RS Grid Mixin、RS 搜索语法和 RS 原生
>    合成终端仍属于 RS 专属 P2；它们可以在 BD-only 隐藏，但核心递归合成、机器委托、磁铁、
>    拾取/喂食/补货、FTB 提交和侧栏存取不能隐藏或静默回退到背包。
>
> **重复与复用审计结论**：没有发现自动生成的重复 BD 文件；新增 BD 适配器集中在
> `storage/bd`，已复用 RSI 的 `StorageBackend`、`StorageSession`、`StorageSnapshot`、
> `StorageOperationResult` 和 `StorageReference`。BD session 中与 RS session 相似的错误映射和
> 分批逻辑是后端边界代码，暂不应复制到业务模块；下一步应把调用方的 `INetwork` 参数替换成
> `StorageReference/StorageSession`，而不是再创建第三套“BD 递归合成”代码。

> **下一批实施顺序**：先完成 `StorageReference` 在配方树请求包、计划缓存、异步链和 ledger 中的
> 传递；再实现计划界面的后端/网络选择和通用翻译键；随后将 `MaterialSources`、中间步骤提取、
> 机器委托退款/产物回存切换到选定 session；最后处理磁铁/绑定物品、侧栏和终端入口。完成这些前，
> BD 后端只能算“已注册的适配器骨架”，不能算 BD 可用。

> **2026-08-23 配方树网络目标接入（第一批已实现）**：`GenericCraftPacket` 现在可以携带版本化的
> `StorageReference(backendId, networkId)`，服务端会优先解析该引用；未指定时保留 RS 终端解析，
> 再按已注册后端选择默认网络。计划响应新增当前网络和可选网络列表，客户端计划界面在多个网络间
> 循环选择，并把选择随下一次预览/下单请求发回服务端。BD-only 预览材料已经通过
> `SessionCraftStorageEndpoint`/`MaterialSources` 读取 BD 快照，不再因为 RS 未安装而静默只看玩家背包。
> 同时新增通用的“存储网络”文案键。该批仍**不等于 BD 执行完成**：递归执行、机器投放、退款和产物回收
> 的旧委托链仍保存 `INetwork`，所以 BD 网络目前只能完成解析/快照入口；下一批必须将 endpoint 传入
> `CraftingResolver`、`ExtractionLedger`、`AsyncCraftChain` 和各机器 delegate，并补齐事务测试。

> 评估对象：RS Integration（下称 RSI）与 BeyondDimensions（下称 BD）  
> 评估日期：2026-08-21  
> RSI 工作树：`D:\sd\rs-integration`  
> BD 工作树：`D:\sd\BeyondDimensions`，分支 `1.20.1`，检查时提交 `6642c5ae`  
> 目标：将 RS 与 BD 建设为可互换、二选一的存储后端。玩家只安装其中任意一个时，RSI 自身提供的递归合成、次元磁铁、机器绑定、补货和转移等核心功能都必须可用；两个后端同时安装时允许玩家明确选择目标网络。

## 1. 执行摘要

### 1.1 结论

**技术上可行，而且可以达到“RS/BD 任装其一，RSI 核心功能等价可用”的目标；但当前版本不能直接在没有 RS 的环境中启动。**

BD 1.20.1 已经具备承担 RSI 主存储后端所需的关键能力：玩家网络解析、持久化网络标识、物品/NBT 精确身份、全量枚举、精确或模糊提取、模拟操作、插入余量、长整型数量、成员关系以及变更通知。RSI 不需要自己重写一个存储系统。

当前障碍来自 RSI 的架构，而不是 BD 的能力：RS 被声明为强制依赖，业务层广泛直接持有 `INetwork`，主模组类和多个公共工具类直接链接 RS 类型，RS 终端 Mixin、网格交互、共鸣磁盘也属于 RS 专属实现。仅将 `mods.toml` 中的 RS 依赖改为可选会导致类加载失败，不能形成可用的无 RS 版本。

### 1.2 推荐决策

建议采用以下产品和架构方向：

1. 将 RSI 的定位由“RS 专属集成”调整为“多存储后端的机器与递归合成集成”。
2. 建立一个只依赖 Minecraft/Forge/RSI 自有类型的 `StorageBackend` 契约。
3. 将 RS 调用集中到 `RefinedStorageBackend` 和 RS 专属模块。
4. 新增 `BeyondDimensionsBackend`，通过 BD 的 `DimensionsNet` 与 `UnifiedStorage` API 工作。
5. RS 和 BD 均声明为可选依赖；运行时注册实际存在的后端，并要求至少一个受支持后端可用。不能把强制依赖简单地从 RS 换成 BD。
6. 将递归合成、次元磁铁、拾取、喂食、补货、机器绑定、材料提取和产物回收定义为后端无关的必达功能，不接受 BD 模式功能降级。
7. 只有直接修改某个存储模组内部界面的增强允许后端分别实现，例如 RS Grid Mixin 与 BD 终端入口；它们不应反向污染核心功能。

### 1.3 总体评级

| 评估项 | 评级 | 说明 |
|---|---:|---|
| 版本兼容性 | 高 | 两边均为 Minecraft 1.20.1、Forge 47.x、Java 17 体系 |
| BD 存储 API 完整度 | 高 | 枚举、插入、提取、模拟、NBT 身份和持久化均已提供 |
| RSI 当前解耦程度 | 低 | 大量业务代码直接使用 `INetwork` 和 RS 权限/API |
| 核心功能迁移可行性 | 高 | RSI 的配方解析和机器执行主体属于自有逻辑 |
| RS UI 功能迁移可行性 | 中 | 能重做，但不能复用现有 RS Mixin |
| 共鸣磁盘原样迁移可行性 | 低 | 实现直接继承 RS 存储磁盘契约，需要重设计 |
| 次元磁铁双后端可行性 | 高 | 吸取和过滤逻辑可复用，只需改造绑定格式与最终插入后端 |
| 数据安全风险 | 中高 | 提取、部分成功、退款、断线恢复必须统一事务语义 |
| 综合实施风险 | 中 | 边界清晰，但改动面较广，适合分阶段完成 |

## 2. 评估范围与假设

### 2.1 纳入范围

- RSI 在没有 RS 时的加载与运行。
- BD 作为物品存储来源和产物回收目标。
- RSI 递归合成、异步合成链、机器委托、补货、转移、自动进食等依赖网络物品的功能。
- RSI 次元磁铁、拾取、喂食、补货等精妙背包兼容升级在 RS-only 与 BD-only 环境中的等价行为。
- 网络选择、玩家授权、缓存、事务和错误恢复。
- RS 专属 UI、Mixin、存储磁盘和终端入口的隔离策略。
- 构建、发布、兼容测试和迁移阶段。

### 2.2 不纳入首期范围

- 将 RSI 的所有 RS 网格视觉增强完整移植到 BD 界面。
- 让 BD 原生支持 RS 的自动合成模式或磁盘格式。
- 跨后端实时镜像或在一次合成中同时消费 RS 与 BD 两个网络。
- 自动迁移 RS 磁盘内容到 BD。
- 修改 BD 的存档格式。

### 2.3 关键假设

- 目标运行环境为 Minecraft 1.20.1 Forge。
- 使用 BD 1.20.1 分支公开 API，不直接访问其私有字段或存档 NBT。
- 一次 RSI 操作只绑定一个明确的存储后端和一个明确的网络。
- 首期以物品为核心；BD 的流体、能量和 Mekanism 化学品存储不自动纳入 RSI 合成物料模型。
- 保留玩家背包优先或网络优先等现有策略，但网络部分通过后端契约实现。
- “二选一”是安装条件，不是代码中硬编码一个全局后端：RS-only 自动使用 RS，BD-only 自动使用 BD，同时安装时由绑定或玩家选择决定。

### 2.4 核心功能等价性定义

本项目目标中的“二选一”必须按用户可见能力验收，而不是只证明两个后端都能调用 `insert/extract`：

| 场景 | RS-only | BD-only | RS + BD |
|---|---|---|---|
| 递归合成与机器链 | 完整可用 | 完整可用 | 使用发起终端或显式选择的网络 |
| 次元磁铁 | 可绑定 RS 网络 | 可绑定 BD 网络 | 每件升级绑定一个明确后端 |
| 拾取/喂食/补货升级 | 可用 | 可用 | 每件升级绑定一个明确后端 |
| 机器绑定与侧栏操作 | 可用 | 可用 | 每台机器绑定一个明确后端 |
| 一键存入与材料补充 | 可用 | 可用 | 不允许隐式跨后端取放 |

核心功能可以有不同的后端入口，但不能出现“安装 BD 时该功能隐藏、配方消失或退回玩家背包”的静默降级。

## 3. 版本与依赖兼容性

### 3.1 版本核对

BD 当前工作树信息：

- Git 分支：`1.20.1`
- Minecraft：`1.20.1`
- Forge：`47.1.33`，声明范围 `[47.1.3,)`
- Mod ID：`beyonddimensions`
- Mod 版本：`0.7.27`
- License：MIT

RSI 当前也是 Minecraft 1.20.1 Forge 项目，因此不存在 1.20.1/1.21.1 API、Forge/NeoForge 或 NBT/Data Component 模型之间的迁移障碍。

### 3.2 BD 对 RS 的关系

BD 自身是独立存储模组，RS 只是它的可选集成模块。BD 的 RS 适配代码位于 `integration.module.rs`，用于让 RS 外部存储读取 BD 网络；这不意味着 BD 核心依赖 RS。

因此，目标组合在依赖层面成立：

```text
Minecraft + Forge + BeyondDimensions + RSI
```

无需安装 RS，也不需要通过 BD 的 RS 通道间接访问存储。RSI 应直接调用 BD 的公开维度网络 API。

### 3.3 构建接入建议

优先级从高到低：

1. 使用 BD 发布的 Maven/API artifact，并对实现类做 `compileOnly`。
2. 若没有稳定 Maven artifact，使用正式发布 JAR 作为 `compileOnly` 本地依赖，测试运行时再加入该 JAR。
3. 开发期间可以使用 Gradle composite build，但不应成为发布构建的唯一方式。
4. 不建议复制 BD API 源码到 RSI，也不建议通过反射访问常规存储操作；这会丢失编译期契约检查。

BD API 兼容性需要按版本范围管理。由于 RSI 将直接使用 `DimensionsNet`、`UnifiedStorage`、`IStackHandler`、`ItemStackKey` 和 `KeyAmount`，这些类型的二进制变更会影响适配器，但不会影响核心业务层。

## 4. BeyondDimensions 能力评估

### 4.1 网络解析

BD 提供：

- `DimensionsNet.getPrimaryNetFromPlayer(Player)`：取得玩家选择的主网络。
- `DimensionsNet.getAllNetFromPlayer(Player)`：列出玩家加入的所有网络。
- `DimensionsNet.getNetFromId(int)`：按持久化 ID 解析网络。
- `DimensionsNet.getUnifiedStorage()`：取得统一存储。

这比 RSI 当前依赖 RS 终端、无线物品、控制器坐标和绑定 NBT 推断网络更适合做稳定绑定。建议 BD 后端使用 `backendId + networkId` 作为持久化引用，例如：

```text
backend = beyonddimensions
network = 42
```

不能只保存玩家当前主网络，因为玩家可以切换主网络；已绑定机器必须继续指向绑定时选择的网络。

### 4.2 物品身份

`ItemStackKey` 表示物品及其 NBT 身份，并可生成 `ItemStack` 副本。它可以映射 RSI 当前的 `StackKey`/`MaterialKey` 精确身份模型。

建议规则：

- 精确提取：`new ItemStackKey(template)`，`fuzzy=false`。
- Ingredient 匹配：先对存储快照逐项执行 `Ingredient.test`，再按匹配到的具体 `ItemStackKey` 提取。
- 标签提取：除非调用方明确接受任意成员，不直接使用模糊提取。
- 数量边界：BD 使用 `long`，RSI/Minecraft `ItemStack` 使用 `int`；适配器必须分批并检查溢出。

### 4.3 枚举与快照

`IStackHandler.getStorage()` 返回 `List<KeyAmount>`，可以替代 RS 的 `IStorageCache<ItemStack>` 列表。

适配时必须注意：

- 不将 BD 内部返回对象直接暴露给异步规划线程。
- 在服务端线程复制为 RSI 自有的不可变 `StoredStack`/`MaterialCount` 快照。
- 数量从 `long` 限制或饱和转换到现有规划器的 `int` 前，要明确策略。
- 大型网络可能有大量种类，继续保留 RSI 的每 tick/版本化缓存，避免每个配方节点重复全量扫描。

BD 还提供存储变更订阅能力，可作为后续缓存失效优化，但首期可以沿用按 tick 快照，降低监听生命周期和服务端关闭清理风险。

### 4.4 插入、提取和模拟

BD 提供统一的：

- `insert(key, amount, simulate)`，返回未插入余量。
- `extract(key, amount, simulate, fuzzy)`，返回实际提取量。
- 通过 `KeyAmount` 返回资源身份和数量。

语义足以实现 RSI 当前 `Action.SIMULATE/PERFORM` 用法，但返回值方向不同：RS 插入返回剩余 `ItemStack`，BD 插入返回 `KeyAmount` 余量。适配器应把它们规范化为统一结果：

```java
record TransferResult(ItemStack transferred, ItemStack remainder) {}
```

核心业务不应自行解释各后端返回值。

### 4.5 权限模型

RS 提供 `Permission.EXTRACT`、`Permission.INSERT` 等操作级权限。BD 当前公开模型主要是：

- owner
- managers
- players/members

直接取得 `UnifiedStorage` 后进行插入、提取，不会自动携带发起玩家上下文。因此 **BD 适配器必须在访问存储前自行验证访问依据**，不能因为知道网络 ID 就允许操作。当前适配器接受 owner、manager、普通 member；另外，玩家背包中已绑定到该网络的 BD `NetedItem` 终端也可作为显式访问依据。

首期建议权限映射：

| RSI 操作 | RS | BD |
|---|---|---|
| 查看/规划 | RS view/extract 相关策略 | owner/manager/member，或持有已绑定该网络的 BD 终端 |
| 提取材料 | `Permission.EXTRACT` | owner/manager/member，或持有已绑定该网络的 BD 终端 |
| 插入/回收 | `Permission.INSERT` | owner/manager/member，或持有已绑定该网络的 BD 终端 |
| 修改绑定/管理设置 | 对应 RS 权限或所有权 | owner 或 manager |

如果 BD 后续增加更细权限，映射只应修改 BD 适配器。

### 4.6 持久化与线程约束

`DimensionsNet` 是 `SavedData`，`UnifiedStorage` 的变更会调用网络 `setDirty()`。RSI 不应直接序列化或修改 BD 存档。

所有实际插入、提取、网络解析和快照采集都应在服务端线程进行。异步线程只处理 RSI 自己复制出的不可变数据。现有异步合成规划若把 `INetwork` 保存进上下文，需要改为保存后端引用或快照，执行阶段重新在服务端线程解析。

## 5. RSI 当前耦合评估

### 5.1 依赖声明

`src/main/resources/META-INF/mods.toml` 当前将 `refinedstorage` 标为 `mandatory = true`。这是无 RS 启动的第一道硬阻断，但不是唯一阻断。

正确顺序是：先完成类加载隔离和后端抽象，再把 RS 改成可选依赖。提前修改元数据只会把“加载器明确拒绝启动”变成 `NoClassDefFoundError` 或 Mixin 目标解析错误。

### 5.2 静态类型耦合规模

检查当前工作树得到：

- Java 文件总数：773。
- 直接导入 `com.refinedmods.refinedstorage` 的文件：123。
- 直接出现 `INetwork` 的文件：84。
- 与 `INetwork`、`RSIntegrationNetwork`、RS 存储缓存、插入/提取及权限相关的检索结果分布在核心合成、机器委托、网络、侧栏、共鸣、自动进食、补货和工具层。

这些数字表示改造是跨模块工程，但不意味着 123 个文件都要重写。大量文件只需要把参数类型从 `INetwork` 改为后端会话，并把插入/提取调用委托给统一服务。

### 5.3 高耦合区域

#### 网络服务

`RSIntegrationNetwork` 同时承担：

- 从 RS 容器、终端、无线物品和绑定解析 `INetwork`。
- 权限检查。
- 网络物品枚举。
- Ingredient 匹配提取。
- 精确 NBT 提取。
- 插入失败退款。

它是最适合先拆分的边界，但不应简单重命名。建议拆成通用 `StorageService`、`StorageResolver` 与 RS 后端适配器。

#### 合成核心

`MaterialSources`、`ResolutionContext`、`CraftPacketUtils`、`ExtractionLedger`、`AsyncCraftChain` 和批量委托广泛传递 `INetwork`。配方图、需求解析和机器调度本质上不依赖 RS，应只接收存储快照、存储会话和统一转移结果。

这是迁移价值最高的区域：一旦解耦，大部分机器集成会同时支持 RS 与 BD。

#### 机器委托

多个 `BatchDelegate` 直接调用 `network.insertItem`，用于材料退款、产物回收和失败恢复。应统一迁移到 `StorageSession.insert` 或现有账本的后端无关入口，避免每个委托自行处理余量。

#### 侧栏和绑定

当前侧栏围绕 RS 终端打开、返回 RS、RS 控制器/网络物品绑定设计。BD 模式下需要：

- 从 BD 终端或独立 RSI 快捷键打开侧栏。
- 绑定 `BD network ID`，而不是 RS 控制器维度和坐标。
- 返回 BD 终端时使用 BD 提供的公开开屏方式；若无稳定 API，则先返回玩家原菜单或不提供“返回终端”。

#### 自动进食、补货与转移

这些功能主要需要枚举、筛选、提取和退款，适合迁移到统一后端。它们是核心合成之后的第二批迁移对象。

#### 次元磁铁与精妙背包网络升级

当前“次元磁铁”是 RSI 注册的 `rs_integration:rs_magnet_upgrade`。配方只需要精妙背包高级磁铁和紫色染料，本身不依赖 RS 材料；真正的 RS 耦合集中在以下位置：

- `RSMagnetUpgradeItem` 只接受潜行右键 RS `ControllerBlockEntity`。
- 绑定 NBT 使用 `RSBlockPos` 与 `RSBlockDimension`。
- `MagnetUpgradeWrapperMixin` 将绑定解析为 RS 控制器坐标。
- `BackpackRSUtils` 直接取得 `INetwork` 并调用 RS 插入、提取和缓存 API。
- 拾取、喂食、补货升级复用同一套 `bindToRS/isBoundToRS` 逻辑。

磁铁范围扫描、过滤槽、虚空升级协作、掉落实体处理、合成产物保护和插入量统计并不依赖 RS，可以原样保留。建议改造为：

```text
物品 ID：rs_integration:rs_magnet_upgrade（保留，兼容已有存档）
显示名：次元磁铁（保持后端中立）
绑定：StorageReference { backendId, networkId }
执行：StorageSession.insert(...)
```

绑定交互建议：

- RS：潜行右键 RS 控制器，生成 RS `StorageReference`。
- BD：BD 终端的潜行右键网络绑定仍由 BD 原生处理；RSI 机器绑定固定走可配置的 Alt+右键（默认 Alt+右键），生成 BD network ID 引用。两条动作相互隔离，避免一次点击同时绑定后又解绑机器。
- RS + BD：点击哪个后端的网络目标就绑定哪个；禁止自动覆盖为另一个后端。
- 旧物品：检测到 `RSBlockPos/RSBlockDimension` 时迁移或按旧 RS 引用读取，保存时升级为新 schema。

`RSMagnetUpgradeItem`、`BackpackRSUtils` 及相关方法名也应逐步改为后端中立名称。Java 类名可以分阶段迁移，但用户存档中的注册 ID 不应更改。

#### 共鸣系统

`ResonanceDiskItem`、`ResonanceDiskWrapper`、`ResonanceDiskFactory` 和被动效果引擎直接实现或遍历 RS 存储磁盘 API。它们不是普通“存储后端调用”，不能通过几个接口方法无损替换。

首期建议：

- 无 RS 环境不注册共鸣磁盘物品、配方、菜单和被动扫描逻辑；或
- 将共鸣能力重构为 RSI 自有持久化组件，分别提供 RS 磁盘载体与 BD 网络载体。

第二种方案工作量和数据迁移风险明显更高，不应阻塞 BD 核心后端上线。

### 5.4 Mixin 与客户端类加载

RSI Mixin 配置中包含 RS 网格、合成管理器、搜索、工具提示和网络物品目标。无 RS 环境中必须由 Mixin plugin 严格跳过所有 RS 目标，同时确保：

- 通用 mixin 类的字段、方法签名和注解中不引用 RS 类型。
- 客户端入口不静态导入 RS Screen、Grid 或 API 类。
- 主模组构造路径不触达 `API.instance()`、RS 注册对象或共鸣类。
- 数据生成和运行时注册分离，避免可选类在注册 lambda 捕获时被加载。

现有 Mixin plugin 可以作为机制基础，但必须通过真实“无 RS 客户端”和“无 RS 专服”启动测试证明隔离完整。

## 6. 功能迁移矩阵

### 6.1 目标等级

| 等级 | 含义 |
|---|---|
| P0 双后端必达 | RSI 自己提供的核心能力；RS-only 和 BD-only 必须完整可用 |
| P1 后端入口等价 | 功能入口会接触存储模组终端 UI，需要分别实现 RS/BD 接入，但最终用户能力必须等价 |
| P2 原生对象重设计 | 功能建立在 RS 私有/专有对象上，不能靠存取适配完成，需要独立设计；不得被误报为已支持 BD |
| 独立于后端 | 功能本身不访问存储，只需保证解耦过程中不回归 |

### 6.2 存储基础与递归合成

| 功能 | 目标 | 当前 RS 耦合 | BD/通用实现与验收 |
|---|---|---|---|
| 玩家网络解析 | P0 | 从 RS Grid、无线物品、控制器和绑定解析 `INetwork` | RS-only 自动解析 RS；BD-only 使用主网络或已绑定 network ID；双装时使用明确上下文 |
| 多网络选择 | P0 | 目前主要围绕单个可访问 RS 网络 | 列出 BD 玩家网络并保存 `StorageReference`；禁止用“RS 优先”猜测 |
| 网络库存快照 | P0 | `IStorageCache<ItemStack>` | BD `getStorage()` 复制为不可变快照；精确保留 NBT，数量使用 `long` |
| 精确/Ingredient/标签匹配 | P0 | RS comparer、storage list、`extractItem` | `ItemStackKey` 精确提取；Ingredient 先筛快照再按具体 key 提取 |
| 插入、模拟、容量余量 | P0 | RS `Action` 和返回 `ItemStack` 余量 | 统一 `StorageSession.insert` 语义，正确转换 BD `KeyAmount` 余量 |
| 权限 | P0 | RS `Permission.EXTRACT/INSERT` | RS 保留细粒度检查；BD 每次验证成员，管理操作要求 manager/owner |
| 递归计划预览 | P0 | 规划上下文直接保存 `INetwork` | 只消费后端无关快照；RS/BD 相同配方得到等价需求图 |
| 原版与 KubeJS 递归合成 | P0 | 材料来源和结果回收直接操作 RS | 从选定会话提取，容器/催化剂/动态 NBT 产物回到同一会话 |
| 异步合成链、并行和负载均衡 | P0 | `AsyncCraftChain`、delegate 参数传递 `INetwork` | 任务保存 `StorageReference`，执行前重解析；不跨线程访问 BD SavedData |
| 取消、失败退款与服务端恢复 | P0 | 退款写回原 RS 网络 | 账本记录 backend + network + exact stack；任何退款只回原后端 |
| 合成进度界面和 HUD | 独立于后端 | 文案和任务来源隐含 RS | 显示后端/网络名；RS-only、BD-only 的进度、取消和恢复行为相同 |
| JEI RSI 合成按钮、卡片、树和总需求 | P0 | 入口经常从 RS Grid 上下文取网络 | 在 BD 终端或独立 RSI 入口也能打开同一预览和启动流程 |
| FTB Quests 递归合成并提交 | P0 | 任务材料从 RS 扣除，退款回 RS | 从选定后端托管/提交/退款，避免 BD 插入与任务进度重复计数 |

### 6.3 机器绑定、机器管理中心与远程操作

| 功能 | 目标 | 当前 RS 耦合 | BD/通用实现与验收 |
|---|---|---|---|
| 网络连接器绑定/解绑机器 | P0 | 连接器代表 RS 网络，机器绑定类型为 `RS_NETWORK` | 连接器保存 `StorageReference`；RS 控制器或 BD 网络/终端均可成为绑定来源 |
| `;` 附近机器批量绑定 | P0 | 从 RS 网络连接器解析网络 | 从手持/背包/饰品中的通用连接器解析后端，保持分片扫描与保护检查 |
| 绑定提示 HUD | P0 | 提示文本和状态写死 RS | 显示目标后端与网络名，绑定/解绑判断基于完整引用 |
| 机器管理中心 `H` | P0 | 打开时同步 RS 网络和绑定状态 | RS-only、BD-only 均可打开；标题/状态不写死 RS；显示当前后端 |
| 机器列表、搜索、拼音与收藏 | P0 | 机器列表与 RS binding 同步 | 列表按当前 `StorageReference` 分组/过滤；收藏继续使用机器身份，不依赖后端 |
| 机器实时状态 | P0 | 请求包通过 RS 网络上下文授权 | 使用绑定会话验证玩家成员和远程操作权限 |
| 远程打开机器 GUI | P0 | 从 RS Screen 进入，关闭后返回 RS 终端 | 远程打开逻辑通用；来源为 BD 时返回 BD 终端或 RSI 上一层界面 |
| 数字键快速选择/打开 | P0 | 依赖机器中心的 RS 列表 | 对当前后端机器列表保持相同行为 |
| `Ctrl` + 左键立即解绑 | P0 | 删除 RS 绑定 | 删除对应 `StorageReference`，不影响同坐标另一个后端的合法绑定 |
| 机器收取产物 | P0 | `MachineCollectPacket` 最终插入 RS | 收取目标为机器绑定会话；容量不足时保留/退款，不得掉物 |
| 标签放入输入槽/补燃料 | P0 | 标签由 RS 侧栏和网络材料驱动 | 从选定后端提取并放入机器，失败按账本回滚 |
| 多台同类机器分担合成 | P0 | worker 可用材料快照来自 RS | worker 调度保持通用，所有节点共享同一后端引用 |
| FTB Chunks/Cadmus 权限和区块策略 | 独立于后端 | 无存储 API 必需耦合 | 原样保留；BD 接入不得绕过领地权限或强制加载区块 |
| 共振背包入口 | P2 | 管理中心直接寻找 RS 共振磁盘 | 若共振系统重设计，再提供后端中立入口；首期不能伪装支持 |

机器管理中心不是 RS Grid 的附属功能。它的机器目录、状态、收藏、远程 GUI、收取和解绑都属于 RSI 自有能力，因此必须在 BD-only 环境完整注册和工作。只有“关闭后返回哪个存储终端”属于后端入口差异。

### 6.4 一键吃、补货与物品消费工作流

| 功能 | 目标 | 当前 RS 耦合 | BD/通用实现与验收 |
|---|---|---|---|
| 一键吃入口和食物选择 UI | P0/P1 | 当前要求打开 RS 合成网格，并从 grid 取得网络 | BD 终端提供等价入口，或使用独立快捷键/面板；不能继续检查 `INetworkAwareGrid` |
| 多样饮食（SolCarrot） | P0 | 扫描 RS 库存并直接提取 | 扫描选定后端快照，选择未记录食物，逐次精确提取 |
| 堆叠暴食 | P0 | 从 RS 取选定食物，失败插回 RS | BD 使用同一数量上限、饱食/effect 策略和退款语义 |
| 膳食均衡（Diet） | P0 | 从 RS 快照选择营养组食物 | 后端只负责候选库存和存取，Diet 逻辑保持一致 |
| 食物/效果黑名单 | 独立于后端 | UI 入口绑定 RS Grid | 偏好数据不变，在两个后端入口共用 |
| 一键吃使用费用 | P0 | 费用只从 RS 扣除 | 从当前 `StorageSession` 扣除，失败不消耗食物、不产生效果 |
| 碗、瓶等容器返还 | P0 | 优先插回 RS | 插回原后端；余量再安全给玩家 |
| 村民交易付款槽补货 | P0 | 背包后从 RS 精确提取 | 背包后从选定后端补足，缺少物加入 JEI 收藏 |
| 附魔台青金石补货 | P0 | RS 权限和精确提取 | 改用通用会话；完成/部分/无权限文案后端中立 |
| 重铸台符石补货 | P0 | RS 权限和精确提取 | 改用通用会话，保留 JEI 缺失收藏 |
| 铁砧记忆材料补货/换槽 | P0 | 通过 RS 网络补足记忆材料 | 从选定后端补足；换槽逻辑保持独立 |
| 天华砧自动补充材料 | P0 | 机器绑定材料源为 RS | 从机器绑定会话补充，与自动锤炼/温控保持协作 |

“一键吃”不应继续定义成“RS 合成网格功能”。正确模型是 RSI 的网络食物消费器：RS Grid 和 BD Terminal 都只是打开或选择 `StorageSession` 的入口。

### 6.5 侧栏、库存交互和转移工具

| 功能 | 目标 | 当前 RS 耦合 | BD/通用实现与验收 |
|---|---|---|---|
| `Y` 远程库存侧栏 | P0 | 名称、包名和同步服务均为 `RSSidePanel*`，快照来自 RS | 服务端发送通用库存快照；RS-only/BD-only 都能显示数量和机器标签 |
| 侧栏搜索、拼音、tooltip、模组 ID | P0 | 数据源为 RS list | 对 BD 快照执行同一客户端索引和过滤 |
| 排序、网格大小和视图模式 | P0 | 包含 RS craftable 标志 | 普通库存排序完整保留；BD 没有原生 craftable 时使用 RSI 配方可制作状态或明确隐藏该筛选 |
| 侧栏点击整组/单个提取 | P0 | packet 直接从 RS 提取 | 通过当前会话精确提取，权限和余量语义一致 |
| 侧栏拖动分配到机器 | P0 | 从 RS 提取后写绑定机器 | 从所选后端提取，机器写入失败按账本退回 |
| 侧栏 `R/U` 打开 JEI | 独立于后端 | 入口显示在 RS 侧栏 | 对 BD 侧栏保持相同操作 |
| 侧栏机器标签与远程打开 | P0 | 标签来自 RS binding 同步 | 使用当前后端绑定集，复用机器管理中心权限 |
| `F` 容器一键存入网络 | P0 | 目标模式为“RS 网络” | 模式改为“网络/精妙背包”；网络目标为当前/绑定后端 |
| `G` 切换传输模式 | P0 | 枚举 RS/背包两种模式 | 单后端时网络/背包；双后端时需要额外选择 RS/BD，不能循环时误选 |
| 自存储保护 | P0 | 阻止 RS 磁盘和绑定升级存入自身 RS | 后端适配器判断 `StorageReference`；阻止 BD 终端/绑定物品存入其自身网络 |
| 世界选取/中键取物 | P0 | 从 RS 网络提取或加入 JEI 收藏 | 从当前后端提取，BD-only 行为和提示等价 |
| RS Grid `Ctrl` 滑动提取 | P1 | 直接 Mixin RS Grid | RS 保留 Mixin；BD Terminal 需要对应交互 hook，或在通用侧栏提供等价滑动提取 |
| JEI `Ctrl+T` 配方传送 | P1 | 传到 RS Crafting Grid | BD 传到 BD 合成终端；无可接受终端时打开 RSI 计划/材料转移界面 |
| RS 搜索历史和 `#/$/@` 搜索 | P1 | 修改 RS Grid 搜索实现 | RS 保留；BD 已有自身搜索，缺少的历史/语法按 BD 客户端适配，不放入 core |
| JEI 拖框收藏/隐藏、模组筛选 | 独立于后端 | 与 RS 同章节但主体为 JEI | 原样保留，不应因无 RS 而禁用 |

### 6.6 神化、FTB Quests 与专用网络工作流

| 功能 | 目标 | 当前 RS 耦合 | BD/通用实现与验收 |
|---|---|---|---|
| Apotheosis 附魔图书馆扫描 | P0 | 扫描绑定 RS 网络全部附魔书 | 扫描图书馆绑定会话快照，按完整 NBT 合并 |
| 图书馆批量导入与退款 | P0 | 从 RS 提取，目标拒绝后退回 RS | 从原后端提取并退款；余量安全给玩家/掉落并记录 |
| Apotheosis 刷怪笼升级 | P0/P1 | 要求手持 RS 网络物品，库存与递归计划来自 RS | 接受任一后端绑定物品/终端上下文，材料与缺失递归制作使用同一会话 |
| FTB Quests 外部物品进度追踪 | P0 | 追踪“实际进入 RS”的插入事件 | 所有通用 `StorageSession.insert` 实际插入均上报；模拟、退款和恢复不计数 |
| FTB Quests 直接物品提交 | P0 | 只从 RS 扣除缺少物 | 从选定后端提交，拒绝时回原后端，避免重复任务进度 |
| JEI FTB 任务提交分类 | P0 | 错误文案和启动上下文写死 RS | 保持分类，网络解析改为通用会话 |
| Distant Worlds 残骸磁铁化 | P0 | 假设次元磁铁写入 RS | 继续产出可吸取实体，由绑定 RS 或 BD 的次元磁铁处理 |

### 6.7 精妙背包升级

| 功能 | 目标 | 当前 RS 耦合 | BD/通用实现与验收 |
|---|---|---|---|
| 次元磁铁 | P0 | 控制器坐标 NBT、`BackpackRSUtils` RS 插入 | 保留物品 ID，绑定通用引用，掉落物直接进入 RS 或 BD |
| 次元拾取 | P0 | 拾取 hook 将物品写入 RS | 通过绑定会话模拟/执行插入，遵守过滤规则和余量 |
| 次元喂食 | P0 | 从 RS cache 选择并提取食物 | 从绑定后端快照选食物，失败退款到原后端 |
| 次元补货/Restock | P0 | 从 RS 枚举、提取并填充背包 | 从绑定后端补充，背包拒绝余量退回原后端 |
| 卸货/存入升级重定向 | P0 | 自动输出直接指向 RS | 指向绑定后端，正确上报实际插入量 |
| 升级过滤/启用/禁用 UI | 独立于后端 | tooltip 和方法名写死 RS | UI 保持，显示后端和网络名；不因 BD-only 隐藏升级 |
| Majrusz 饰品压缩增强 | 独立于后端 | 不要求网络存取 | 原样保留 |

### 6.8 各模组机器和特殊自动化

所有已经进入 RSI 递归合成体系的机器 delegate 都属于 P0 双后端范围，包括但不限于原版熔炉/炼药、Iron Furnaces、Botania、Ars Nouveau、Goety、Malum、Embers、Aetherworks、Forbidden & Arcanus、Farmer's Delight/Respite、Youkai's Homecoming、Distant Worlds、Touhou Little Maid、Wizards Reborn、Crock Pot、Farming for Blockheads、Eidolon、Avaritia 等。

这些集成的配方识别、机器结构检查、启动和产物判定大多不依赖 RS；耦合主要出现在：

- 从网络准备材料。
- 将未使用材料和容器退款。
- 将机器产物回收到网络。
- 执行前检查网络是否仍然可用。
- 异步任务恢复时重新取得网络。

验收标准不是“通用合成能用就算完成”，而是现有支持清单中的每种 delegate 至少在 BD-only 环境跑过一个成功路径，并对风险较高的多方块/流体/动态 NBT 机器覆盖失败退款路径。

### 6.9 真正依赖 RS 原生对象的功能

| 功能 | 等级 | 原因 | 处理方向 |
|---|---|---|---|
| RS Grid 搜索/历史/过滤 Mixin | P1 | 目标类只在 RS 存在 | RS 适配保留；BD Terminal 单独适配等价体验 |
| RS Grid 滑动提取和原生配方传送 | P1 | 注入 RS 容器/消息 | RS/BD 分别接终端，核心提取仍走通用会话 |
| RS 原生自动合成任务 Mixin | P1 | 注入 RS CraftingManager/Task | RS 保留；BD 模式使用 RSI 自己的递归合成引擎，不依赖 BD 原生自动合成 |
| 共振存储盘物品和磁盘驱动器 | P2 | 直接实现 RS `IStorageDisk` | 保留为 RS 扩展，或另行设计“共振核心/网络槽”供 BD 承载 |
| 共振盘唯一性、共振背包和被动扫描 | P2 | 从 RS 磁盘驱动器枚举 wrapper | 必须重设计载体和唯一性，不能只替换 insert/extract |
| RS 磁盘快捷拆解保护 | RS-only | 只针对 RS 磁盘行为 | 无 RS 时不注册该 hook 即可 |

除上表 P2 项目外，报告中列出的 RSI 自有用户功能都应进入 RS-only、BD-only、RS+BD 三套验收矩阵。包名、类名、翻译键中保留 `RS` 不代表允许功能继续只支持 RS；这些内部命名应逐步中立化，注册 ID 则优先保留以兼容存档。

### 6.10 文案、按键和产品命名

当前大量用户文本写死“RS 网络”“RS 合成网格”“一键存入 RS”，这会造成两类问题：一部分只是错误显示，另一部分实际对应代码中的 RS Grid 类型检查。迁移必须同时审计：

- 自动进食的“必须打开 RS 合成网格”“RS 网格未激活”。
- 侧栏、机器中心、世界选取、滑动提取和容器转移的按键名称与结果提示。
- 村民、附魔、重铸、铁砧记忆、Apotheosis 和 FTB Quests 的无网络/无权限提示。
- 次元升级 tooltip 中的“绑定 RS 控制器”和“送入 RS 网络”。
- 配方警告、退款、材料缺失和产物回收中的 RS 文案。

建议显示层使用动态后端名称：

```text
已绑定：Refined Storage / 主基地
已绑定：Beyond Dimensions / 网络 42
已存入 Beyond Dimensions：128 个物品
没有可用的存储网络
```

兼容原则：

- `rs_integration` mod ID、物品注册 ID、旧翻译键和配置键优先保留，避免破坏存档、配方和整合包脚本。
- Java 类和新服务使用后端中立名称；旧 API 可保留过渡委托。
- `displayName = RS Integration` 在 BD-only 产品中容易误导，正式发布前应单独决定是否改显示名称；这不要求修改 mod ID。
- 所有用户可见“RS”文本都必须分类为“确实只描述 RS 原生功能”或“应替换为网络/动态后端”，不能全局盲目替换。

## 7. 推荐目标架构

### 7.1 分层

```text
UI / Packet / Machine Delegate / Crafting
                    |
             StorageService
                    |
        StorageSession / StorageSnapshot
                    |
       +------------+-------------+
       |                          |
RefinedStorageBackend     BeyondDimensionsBackend
       |                          |
   RS INetwork             BD DimensionsNet
```

核心层不得出现以下类型：

- `com.refinedmods.refinedstorage.*`
- `com.wintercogs.beyonddimensions.*`
- RS `Action`、`Permission`、`IStorageCache`
- BD `ItemStackKey`、`KeyAmount`、`UnifiedStorage`

这些类型只存在于各自适配器包。

### 7.2 建议契约

以下是概念接口，不是最终签名：

```java
public interface StorageBackend {
    String id();
    Optional<StorageSession> resolveForPlayer(ServerPlayer player);
    Optional<StorageSession> resolve(ServerPlayer player, StorageReference reference);
}

public interface StorageSession {
    StorageReference reference();
    boolean canView(ServerPlayer player);
    boolean canExtract(ServerPlayer player);
    boolean canInsert(ServerPlayer player);
    StorageSnapshot snapshotItems();
    ItemStack extractExact(ServerPlayer player, ItemStack template, int amount, boolean simulate);
    List<ItemStack> extractMatching(ServerPlayer player, Ingredient ingredient, int amount,
                                    boolean simulate);
    ItemStack insert(ServerPlayer player, ItemStack stack, boolean simulate);
}

public record StorageReference(String backendId, String networkId) {}
```

接口设计原则：

- 不暴露后端原生对象。
- 调用显式携带玩家，避免绕过权限。
- 模拟和执行共享同一语义。
- 插入返回余量；提取返回实际取得物。
- Ingredient 匹配可能涉及多个 NBT 变体，因此不能假定只返回一个堆栈。
- `StorageReference` 可序列化到机器绑定、异步任务和玩家物品。

### 7.3 后端选择策略

推荐优先级：

1. 操作由已绑定机器发起：使用机器保存的 `StorageReference`。
2. 操作由特定终端/菜单发起：使用该菜单所属后端。
3. 操作由玩家快捷键发起：使用玩家显式选择的 RSI 首选后端。
4. 只有一个后端可用：可以自动选择。
5. RS 和 BD 同时存在且没有明确上下文：拒绝猜测并要求玩家选择。

不要用“RS 优先、找不到再 BD”作为默认规则。它会让材料从错误网络消失，也会使绑定和权限行为难以解释。

对于次元磁铁等物品型功能，后端选择必须保存在该物品自己的绑定中，不能每 tick 根据当前已安装模组或玩家主网络重新猜测。这样同一玩家可以携带一个绑定 RS 的背包和另一个绑定 BD 的背包，行为仍然确定。

### 7.4 绑定数据迁移

现有 RS 绑定数据需要版本化兼容：

```text
schema = 2
backend = refinedstorage | beyonddimensions
network_id = ...
display_name = ...
```

旧数据没有 `backend` 时按 `refinedstorage` 解释。BD 绑定使用网络 ID，不保存或依赖网络方块坐标。加载绑定时验证：

- 后端当前已安装。
- 网络存在且未删除。
- 发起玩家仍是成员。
- 异步任务恢复时网络身份与原任务一致。

## 8. 事务、一致性与数据安全

### 8.1 不能只做 API 映射

RSI 的机器合成可能经历：规划、提取、放入机器、等待、收集产物、插回网络、失败回滚。后端适配只解决单次存取，不自动保证整个流程原子性。

必须保留并加强 `ExtractionLedger` 类似的账本：

- 每次实际提取记录后端、网络、物品精确身份和数量。
- 退款必须回到同一后端和同一网络。
- 插入余量不能静默丢弃。
- 玩家离线、网络删除、后端卸载时进入可恢复状态。
- 最终无法回收时按既有安全策略放入玩家、机器安全槽或世界掉落，并记录诊断。

### 8.2 模拟与执行之间的变化

任何后端的 `simulate=true` 都不能当成后续成功保证。规划完成到执行之间，其他玩家或自动化可能改变存储。

执行阶段应：

1. 重新解析网络和权限。
2. 按精确物品身份逐笔执行。
3. 对部分提取建立账本。
4. 若无法取得完整需求，回滚已提取物。
5. 只有全部材料成功进入受控状态后才启动机器。

### 8.3 long/int 数量

BD 单种资源数量可达到 `long`，而 Minecraft `ItemStack` 及 RSI 多处使用 `int`。建议：

- 快照计数使用 `long`，规划需求可继续使用受上限保护的 `int`。
- 向规划器暴露时使用饱和转换，而不是强制转换溢出。
- 实际提取按 `Integer.MAX_VALUE` 以下且符合业务限制的批次进行。
- 所有 `long -> int` 转换使用显式检查。

## 9. 模组加载与发布模型

### 9.1 推荐：单 JAR，双可选后端

优点：用户只安装一个 RSI；RS 与 BD 可以独立或同时存在。缺点是类加载隔离要求严格。

要求：

- `mods.toml` 中 RS、BD 均 `mandatory=false`、`ordering=AFTER`。
- Forge 1.20.1 元数据不能表达“RS 或 BD 至少安装一个”的 OR 依赖；因此由 RSI 在运行时检测。两个都没有时应给出明确错误/受限模式，不能让可选类加载崩溃充当提示。
- 主入口只通过不含可选类型签名的模块注册器加载后端。
- RS/BD 客户端类、事件订阅者、注册对象和 Mixin 都位于独立包。
- 启动时若两个后端均不存在，显示明确错误或仅启用完全不依赖存储的功能。

### 9.2 备选：core + 两个兼容 JAR

```text
rsi-core.jar
rsi-refinedstorage.jar
rsi-beyonddimensions.jar
```

类加载隔离更可靠，依赖关系更清晰，但发布和用户安装复杂。只有当单 JAR 隔离在 Forge 1.20.1 上持续不稳定时才建议采用。

### 9.3 不推荐：直接把强制依赖从 RS 改成 BD

这种方案短期看改动较少，实际上仍需移除所有 RS 静态链接；完成后却只能支持 BD，还会损失现有用户。后端抽象的主要成本已经发生，没有理由丢弃 RS 适配器。

## 10. 分阶段实施方案

### 阶段 0：建立基线

目标：冻结当前 RS 行为并建立无 RS 启动测试框架。

- 记录 RS-only 客户端、专服和核心合成回归场景。
- 增加可选依赖类加载检查。
- 为枚举、精确提取、部分提取退款、插入余量建立契约测试。
- 不修改现有功能行为。

验收：当前 RS 版本测试全部通过，测试夹具可以在不加载 RS 类的 JVM 路径验证 core。

### 阶段 1：抽象存储契约

目标：核心业务不再接收 `INetwork`。

- 引入 `StorageReference`、`StorageSnapshot`、`StorageSession`、`StorageService`。
- 实现 `RefinedStorageBackend`，先保持行为一致。
- 迁移 `RSIntegrationNetwork` 中的通用枚举/提取/插入逻辑。
- 迁移 `MaterialSources`、`ExtractionLedger`、`PlayerUtils`、`TrackedNetworkInsertion`。

验收：RS 环境功能不回归；核心接口和数据模型不引用 RS 包。

### 阶段 2：迁移合成核心与机器委托

目标：递归规划、异步链和机器执行只依赖通用契约。

- 从 `ResolutionContext`、`CraftingResolver`、`AsyncCraftChain` 移除 `INetwork`。
- 批量迁移各 `BatchDelegate` 的退款与产物回收。
- 将异步任务持久化信息改为 `StorageReference`。
- 统一部分成功和回滚行为。

验收：所有现有 RS 合成场景通过；静态检索显示核心 crafting 包不再导入 RS 类型。

### 阶段 3：实现 BD 后端

目标：在无 RS 环境完成核心递归合成。

- 解析主网络和绑定网络。
- 实现成员验证、快照、精确提取、Ingredient 匹配、插入和模拟。
- 处理 long/int 边界。
- 增加 BD 删除网络、切换主网络、失去成员身份的失败路径。

验收：只安装 Forge + BD + RSI 时能启动客户端和专服，并完成至少一个原版递归合成和一个外部机器合成。

### 阶段 4：机器绑定与机器管理中心

目标：机器工作流不再依附 RS 终端，并在 BD-only 环境完整可用。

- 通用网络连接器和版本化 `StorageReference` 绑定。
- 单机绑定、附近批量绑定、解绑和失效清理。
- 机器管理中心列表、搜索、收藏、状态、数字键和立即解绑。
- 远程打开 GUI、返回来源界面、收取产物、标签输入和补燃料。
- BD 网络绑定与后端选择 UI。

验收：RS-only 和 BD-only 中机器管理中心功能项逐项等价；RS+BD 中机器按绑定后端分组且不串库。

### 阶段 5：侧栏、转移与精妙背包升级

目标：所有随身库存查看、提取和自动转移工具使用通用后端。

- `Y` 侧栏的库存同步、搜索、排序、提取和拖动分配。
- 世界选取、一键存入、容器转移与自存储保护。
- 次元磁铁、拾取、喂食、补货升级的 `StorageReference` 绑定与双后端执行。
- 旧 `RSBlockPos/RSBlockDimension` NBT 兼容读取和迁移。
- 精妙背包卸货/存入升级的后端重定向。
- 独立于 RS Screen 的侧栏入口，以及 BD 终端入口。

验收：三种安装组合分别验证；次元磁铁必须把掉落物直接送入绑定网络，侧栏点击/拖动和容器转移必须只操作当前会话。

### 阶段 6：一键吃、补货和专用网络工作流

目标：完成其余所有 P0 网络消费和提交功能。

- 一键吃三种模式、黑名单、费用和容器返还。
- 村民、附魔台、重铸台、铁砧记忆和机器自动补货。
- Apotheosis 图书馆扫描/导入/退款和刷怪笼升级。
- FTB Quests 外部插入进度、直接提交、递归制作和退款。
- Distant Worlds 残骸磁铁化等依赖网络工具的特殊兼容。
- 所有现存 machine delegate 的 BD-only 成功路径和高风险退款路径验证。

验收：第 6 节所有 P0 项逐项通过 RS-only、BD-only、RS+BD 验收；不再存在面向用户的“需要 RS Grid/RS 网络”硬编码前置条件。

### 阶段 7：P1/P2 后端增强

- BD 终端中的滑动提取、配方传送、搜索历史等 P1 等价体验。
- BD 终端内嵌 RSI 标签页或按钮。
- 变更监听驱动的快照缓存失效。
- 共鸣能力的后端无关重设计。
- 流体/能量/化学品材料模型。

这些不应成为独立版首次交付的前置条件。

## 11. 测试策略

### 11.1 启动矩阵

| 环境 | 客户端 | 专服 | 预期 |
|---|---:|---:|---|
| RSI + RS | 必测 | 必测 | 保持当前完整功能 |
| RSI + BD | 必测 | 必测 | 所有 P0 功能可用，无 RS 类加载错误 |
| RSI + RS + BD | 必测 | 必测 | 明确选择后端，不串库 |
| RSI only | 必测 | 必测 | 明确提示无存储后端，或只启用无存储功能 |

### 11.2 后端契约测试

同一套测试分别运行在 RS 和 BD 适配器上：

- 空网络枚举。
- 相同物品不同 NBT 的精确区分。
- Ingredient 匹配多个变体。
- 模拟不改变存储。
- 请求数量大于库存时的部分结果。
- 插入容量不足时返回准确余量。
- 提取后退款恢复原数量。
- 无权限玩家不能查看、提取或插入。
- 网络在操作中被删除或变为不可用。
- 数量超过 `Integer.MAX_VALUE` 时不溢出。

### 11.3 合成与恢复测试

- 原版递归合成。
- 多级机器链。
- 同一材料由玩家背包和网络共同提供。
- 执行前库存被其他玩家改变。
- 机器启动前部分提取失败。
- 产物插入失败。
- 玩家中途离线。
- 服务端重启后任务恢复。
- 绑定网络与当前主网络不同。
- RS 与 BD 同时安装，确认只操作选定网络。
- 同一玩家携带两个次元磁铁，分别绑定 RS 和 BD，确认互不串库。
- BD-only 环境下次元磁铁执行模拟插入、完整插入和容量不足余量处理。
- 旧 RS 次元磁铁 NBT 加载、继续工作并升级为新绑定 schema。
- 拾取、喂食、补货升级分别在 RS-only 和 BD-only 环境执行。

### 11.4 用户功能等价测试

以下测试是发布门槛，不得用后端契约单元测试代替：

- 机器连接器单机绑定、附近批量绑定、解绑和失效清理。
- 机器管理中心搜索、收藏、状态同步、数字键、远程 GUI、收取产物和标签投料。
- BD 来源打开远程机器后正确返回 BD/RSI 界面，不尝试加载 RS Screen。
- `Y` 侧栏的库存显示、拼音/ID 搜索、排序、整组/单个提取和拖动分配。
- `F/G` 容器转移、网络/背包模式、自身网络介质保护和容量不足余量。
- 世界选取和缺失物品加入 JEI 收藏。
- 一键吃多样饮食、堆叠暴食、膳食均衡、两类黑名单、费用不足和容器返还。
- 村民交易、青金石、重铸符石、铁砧记忆和机器材料补货。
- Apotheosis 图书馆全量扫描、筛选导入、目标拒绝退款和网络容量不足退款。
- Apotheosis 刷怪笼多升级预览、已有材料消费、缺失材料递归制作和上下文失效。
- FTB Quests 实际插入进度、模拟不计数、直接提交、拒绝退款和防重复计数。
- 次元磁铁、次元拾取、次元喂食、次元补货和卸货重定向。
- 第 6.8 节所有已支持 machine delegate 的代表成功路径；多方块、流体、动态 NBT 和世界掉落型机器覆盖失败恢复。

每项都至少跑 RS-only 与 BD-only；涉及后端选择或持久绑定的项还必须跑 RS+BD。

### 11.5 静态隔离检查

建议在构建中加入规则：

- `core`、`crafting`、通用 `machine` 包禁止导入 RS/BD 类型。
- BD 包禁止导入 RS 类型，RS 包禁止导入 BD 类型。
- 无 RS 测试 classpath 执行主模组类加载。
- Mixin plugin 针对缺失目标模组的决策有单元测试。
- 扫描 P0 包和用户文本中的 `INetwork`、`RSIntegrationNetwork`、`INetworkAwareGrid` 及硬编码“RS 网络”前置条件；允许项必须列入 RS 专属白名单。

## 12. 风险登记

| 风险 | 概率 | 影响 | 缓解措施 |
|---|---:|---:|---|
| 仅改依赖声明导致无 RS 崩溃 | 高 | 高 | 完成隔离后最后修改 `mods.toml` |
| BD 存储 API 版本发生二进制变化 | 中 | 中 | API 集中在单适配器，锁定兼容版本范围 |
| 知道 network ID 即绕过成员权限 | 中 | 高 | 每次会话解析和执行重新校验成员 |
| 部分提取或退款失败造成物品丢失 | 中 | 高 | 统一账本、精确身份、余量处理和恢复测试 |
| RS 与 BD 同装时选错网络 | 高 | 高 | 显式后端选择，禁止隐式优先级猜测 |
| 次元磁铁旧绑定失效或串库 | 中 | 高 | 保留物品 ID，版本化 NBT，旧 RS 绑定兼容迁移 |
| BD long 数量转换溢出 | 中 | 高 | 核心计数改 long 或使用饱和转换 |
| 异步线程访问 BD SavedData | 中 | 高 | 快照在服务端线程复制，执行回主线程 |
| RS Mixin 在无 RS 环境解析失败 | 中 | 高 | plugin 跳过、包隔离、无 RS 启动测试 |
| 共鸣系统阻塞核心迁移 | 中 | 中 | 首期明确为 RS 专属功能 |
| UI 入口仍依赖 RS GridScreen | 高 | 中 | 提供独立快捷键/菜单入口后再迁移侧栏 |
| 功能注册因无 RS 被整体跳过 | 高 | 高 | 按功能所有者拆注册条件；P0 只检查任一后端，不检查 RS |
| 文案和按键仍把“网络”写死为 RS | 高 | 中 | 翻译键可兼容保留，显示文本统一为网络/具体后端名 |

## 13. 工作量评估

以下是相对规模，不是按日历时间承诺：

| 工作包 | 规模 | 说明 |
|---|---:|---|
| 后端契约和 RS 适配器 | 中 | 决定后续迁移质量，需高覆盖测试 |
| 合成核心去 `INetwork` | 大 | 调用链深，涉及规划、执行和恢复 |
| 机器委托迁移 | 大 | 文件多但模式重复，适合机械化推进 |
| BD 适配器 | 中 | API 能力完整，主要难点是权限与数量语义 |
| 可选依赖和类加载隔离 | 中高 | 需要客户端、专服、Mixin 三方面验证 |
| 侧栏与绑定 UI | 中高 | 现有交互以 RS 终端为中心 |
| 机器管理中心与远程 GUI | 中高 | 目录逻辑可复用，授权、来源返回和同步需后端化 |
| 次元磁铁及网络升级 | 中高 | 扫描逻辑可复用，需重构绑定、存取与旧 NBT |
| 一键吃与各类补货 | 中高 | 入口、库存扫描、费用、退款和文案均需迁移 |
| 神化/FTB 专用工作流 | 中高 | 事务路径复杂，需要单独端到端验证 |
| 侧栏/转移/世界选取 | 中高 | packet 与客户端状态大量使用 RS 命名和上下文 |
| 共鸣系统后端无关化 | 大 | 独立设计项目，建议延期 |

第 6 节所有 P0 项都属于本目标的核心验收范围，不应为缩短周期从 BD-only 版本中删除。可以发布内部开发里程碑逐步验证，但正式“RS/BD 二选一”版本必须跨过完整 P0 门槛。可以延期的是存储模组内部 UI 的 P1 深度增强和共鸣磁盘 P2 重设计。

## 14. 建议的首个可交付版本

首个 RS/BD 二选一版本建议定义为：

### 必须包含

- 无 RS 客户端和专服可以启动。
- 自动识别或由玩家选择 BD 主网络。
- 网络物品快照参与递归配方规划。
- 从 BD 精确提取材料并将产物插回同一网络。
- 现有原版及所有已支持模组机器 delegate 均使用 BD 材料源和产物目标。
- 机器连接器、附近绑定、机器管理中心、收藏、状态、远程 GUI 和产物收取完整可用。
- `Y` 侧栏、点击/拖动提取、世界选取和容器一键转移完整可用。
- 一键吃三种模式、费用、黑名单和容器返还完整可用。
- 村民、附魔、重铸、铁砧记忆和机器补货完整可用。
- Apotheosis 图书馆、刷怪笼升级与 FTB Quests 网络工作流完整可用。
- 次元磁铁可绑定 BD 网络，并把符合过滤条件的掉落物直接插入 BD。
- 次元磁铁、拾取、喂食和补货升级在 RS-only 与 BD-only 环境均注册且可用。
- 旧 `rs_integration:rs_magnet_upgrade` 物品和 RS 绑定数据继续有效。
- 完整的部分失败退款与错误提示。
- 机器绑定持久化 BD network ID。
- RS-only 环境无回归。

### 明确不包含

- RS 网格搜索、过滤、滑动提取等 Mixin 功能在 BD UI 中的复刻。
- RS 自动合成任务 Mixin。
- 共鸣存储磁盘在 BD 中的等价物。
- 多后端联合库存。
- BD 流体、能量和化学品进入递归物料图。

这个范围才足以声称两个存储后端在 RSI 自有核心功能上等价。中间阶段可以先验证递归合成和磁铁，但不能以此替代机器管理中心、一键吃、侧栏、补货、神化和 FTB 工作流的最终迁移。

## 15. 最终建议

批准该方向，但将项目定义为 **“存储后端抽象与 BD 适配”**，而不是“删除 RS 依赖并替换几个 API”。

推荐实施顺序为：

1. 用通用契约包住现有 RS 行为。
2. 让合成核心和机器委托不再认识 `INetwork`。
3. 实现 BD 适配器并完成递归合成的 BD-only 核心闭环。
4. 迁移机器绑定、机器管理中心、远程 GUI、收取与机器标签工作流。
5. 迁移侧栏、容器转移、世界选取、次元磁铁及精妙背包网络升级。
6. 迁移一键吃、全部补货、Apotheosis 和 FTB Quests 专用工作流。
7. 对所有 machine delegate 和 P0 功能执行三环境验收，再修改最终依赖元数据/发布标记。
8. 对 BD Terminal 实现 P1 等价入口；共鸣系统按 P2 单独立项。

完成前七步后，才能正式宣称 RSI 在 RS 与 BD 之间实现二选一：递归合成、机器管理中心、一键吃、侧栏、补货、精妙背包升级、神化/FTB 工作流和全部机器集成都由选定后端支撑，同时现有 RS 用户无回归。这个结构也为未来接入 AE2 或其他存储系统保留了清晰边界。

## 16. 施工级阶段清单

本节把上一节的架构阶段细化为可以建立分支、提交和验收的任务。文件列表是按当前工作树检查得到的首批边界，不代表每个文件最终都要大幅重写；凡是“机械迁移”都应在编译器和契约测试保护下逐批完成。

### 16.1 阶段 0：基线和依赖清点

**目标**：在任何接口改动前锁定 RS 当前行为，并建立“无 RS 不崩溃”的失败证据。

**主要文件/目录**：

- `src/main/resources/META-INF/mods.toml`
- `src/main/resources/rs_integration.mixins.json`
- `src/main/java/com/huanghuang/rsintegration/mixin/plugin/RSIntegrationMixinPlugin.java`
- `src/test/java/com/huanghuang/rsintegration/NetworkPresenceContractTest.java`
- `src/test/java/com/huanghuang/rsintegration/network/PlayerNetworkResolutionCacheTest.java`
- `src/test/java/com/huanghuang/rsintegration/util/TrackedNetworkInsertionTest.java`
- `src/test/java/com/huanghuang/rsintegration/crafting/ExecutionEquivalenceTest.java`

**任务**：

1. 建立 RS-only 的功能冒烟清单：网络解析、递归原版配方、一个机器 delegate、一次退款、侧栏同步、机器中心打开、自动进食请求、次元磁铁插入。
2. 在 `NetworkPresenceContractTest` 增加后端存在性策略测试，先不改变生产依赖。
3. 对所有 Mixin 目标按 `ModList` 建立白名单/跳过测试。
4. 生成一次 RS 耦合清单，作为后续静态检查基线。

**提交边界**：只增加测试、诊断和清单，不改业务行为。  
**完成判据**：`./gradlew.bat test --no-daemon` 通过；RS-only 客户端/专服冒烟通过；无 RS 的类加载实验至少能确认当前失败点。

### 16.2 阶段 1：定义后端核心模型

**目标**：建立不引用 RS 或 BD 的最小契约，使后续代码有统一迁移目标。

**新增目录建议**：`src/main/java/com/huanghuang/rsintegration/storage/`

**新增类型建议**：

- `StorageBackendId`
- `StorageReference`
- `StorageSnapshot`
- `StoredItem`
- `StorageSession`
- `StorageBackend`
- `StorageBackendRegistry`
- `StorageOperationResult`
- `StoragePermission`

**建议先实现的接口能力**：

```text
resolveForPlayer(player)
resolve(reference, player)
snapshotItems()
hasExact(stack)
extractExact(player, stack, amount, simulate)
extractMatching(player, ingredient, amount, simulate)
insert(player, stack, simulate)
canView/canExtract/canInsert(player)
```

**必须加的测试**：

- `src/test/java/com/huanghuang/rsintegration/storage/StorageReferenceTest.java`
- `StorageOperationResultTest.java`
- `StorageSnapshotTest.java`
- `StorageBackendRegistryTest.java`

**提交边界**：只增加接口、值对象和纯单元测试；禁止把 `INetwork` 或 BD 类型塞进契约。  
**完成判据**：核心 `storage` 包可在不含 RS/BD classpath 的测试中编译。

### 16.3 阶段 2：把现有 RS 封装成第一个后端

**目标**：不改变 RS 用户行为，先把现有存取代码集中到 RS 适配器。

**首批迁移文件**：

- `src/main/java/com/huanghuang/rsintegration/network/RSIntegrationNetwork.java`
- `src/main/java/com/huanghuang/rsintegration/util/PlayerUtils.java`
- `src/main/java/com/huanghuang/rsintegration/util/TrackedNetworkInsertion.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/MaterialSources.java`
- `src/main/java/com/huanghuang/rsintegration/crafting/ExtractionLedger.java`
- `src/main/java/com/huanghuang/rsintegration/util/BackpackRSUtils.java`

**拆分方式**：

1. `RSIntegrationNetwork` 保留为兼容 facade，但内部调用 `RefinedStorageBackend`。
2. `IStorageCache` 到 `StorageSnapshot` 的转换只存在于 RS 适配器。
3. `Action.SIMULATE/PERFORM`、RS `Permission` 和 `INetwork` 不得离开 `storage/rs/`。
4. `BackpackRSUtils` 先改为 `BackpackStorageUtils` 的通用外壳，保留旧静态方法作为过渡委托。
5. 所有插入统计统一经 `StorageSession.insert`，FTB 任务进度只接收实际插入结果。

**主要测试**：

- 扩展 `TrackedNetworkInsertionTest` 覆盖 simulate/perform/余量。
- 扩展 `ExecutionEquivalenceTest` 覆盖 exact extraction、退款和重复调用。
- 新增 `RefinedStorageBackendContractTest`，运行现有 RS 夹具。

**提交边界**：`storage` 契约、RS 适配器、旧 facade 三个提交；不要在同一提交迁移机器 delegate。  
**完成判据**：`crafting`、`sidepanel` 之外的业务行为与当前 RS 版本一致，且核心公共类型开始使用 `StorageSession`。

### 16.4 阶段 3：迁移递归合成和机器执行核心

**目标**：让 RSI 自己的递归规划和所有机器 delegate 只依赖通用会话。

**核心文件**：

- `crafting/MaterialSources.java`
- `crafting/ResolutionContext.java`
- `crafting/CraftingResolver.java`
- `crafting/CraftPacketUtils.java`
- `crafting/AsyncCraftChain.java`
- `crafting/AsyncCraftManager.java`
- `crafting/ExtractionLedger.java`
- `crafting/batch/AbstractBatchDelegate.java`
- `crafting/batch/GenericBatchDelegate.java`
- `crafting/batch/GenericCraftPacket.java`
- `crafting/batch/LegacyFlatExecutionService.java`
- `crafting/planning/PlanningStateValidator.java`

**机器 delegate 迁移顺序**：

1. `mods/vanilla/`、`mods/ironfurnaces/`、`mods/farmersdelight/`、`mods/farmersrespite/`：验证通用输入/输出模式。
2. `mods/botania/`、`mods/arsnouveau/`、`mods/goety/`、`mods/malum/`：验证多方块、魔力、动态产物和人工确认。
3. `mods/embers/`、`mods/aetherworks/`、`mods/forbidden/`、`mods/wizardsreborn/`：验证计划状态和延迟启动。
4. `mods/youkaishomecoming/`、`mods/immortalersdelight/`、`mods/distantworlds/`、`mods/touhoulittlemaid/`、`mods/eidolon/`、`mods/avaritia/`、`mods/crockpot/`、`mods/farmingforblockheads/`、`mods/crabbersdelight/`：按同一模板迁移。

**每个 delegate 的固定改法**：

- `INetwork network` 参数改为 `StorageSession session`。
- `network.extractItem` 改为 `session.extractExact/extractMatching`。
- `network.insertItem` 改为 `session.insert`。
- 退款、容器回收和产物回收必须使用同一 `StorageReference`。
- 禁止 delegate 自己解析玩家网络或检查 RS 权限。

**完成判据**：每批 delegate 编译后都有至少一个成功/失败测试；`rg "com.refinedmods|INetwork" src/main/java/.../crafting` 只剩 RS 适配白名单。

### 16.5 阶段 4：实现 BeyondDimensions 后端

**目标**：BD-only 环境完成递归合成闭环。

**新增目录建议**：`src/main/java/com/huanghuang/rsintegration/storage/bd/`

**主要实现类型**：

- `BeyondDimensionsBackend`
- `BeyondDimensionsSession`
- `BeyondDimensionsReferenceCodec`
- `BeyondDimensionsPermissionPolicy`
- `BeyondDimensionsItemCodec`
- `BeyondDimensionsSnapshotAdapter`

**对应 BD API**：

- `DimensionsNet.getPrimaryNetFromPlayer`
- `DimensionsNet.getAllNetFromPlayer`
- `DimensionsNet.getNetFromId`
- `DimensionsNet.getUnifiedStorage`
- `UnifiedStorage.getStorage/insert/extract`
- `ItemStackKey`
- `KeyAmount`

**实现顺序**：

1. 先实现固定 network ID 的解析和成员校验。
2. 再实现 `getStorage()` 到不可变快照的转换。
3. 再实现精确提取和余量插入。
4. 最后实现玩家主网络选择、多网络 UI 和绑定网络失效处理。

**测试**：新增 `BeyondDimensionsBackendContractTest`、`BeyondDimensionsItemCodecTest`、`BeyondDimensionsReferenceCodecTest`；在 BD 依赖运行时执行真实存储集成测试。

**完成判据**：只安装 Forge + BD + RSI 时，能从 JEI 进入递归计划，完成原版递归配方、一个通用机器和一个动态产物机器，并验证取消退款。

### 16.6 阶段 5：机器绑定和机器管理中心

**目标**：机器中心不再隐含“RS 终端是唯一来源”。

**主要文件**：

- `network/binding/BindingStorage.java`
- `network/binding/BindingEventHandler.java`
- `network/binding/NearbyBindingService.java`
- `network/binding/AltarBindingRegistry.java`
- `network/binding/RSBindingHook.java`
- `sidepanel/data/BindingCache.java`
- `sidepanel/data/MachineStatusKey.java`
- `sidepanel/client/MachineTabHandler.java`
- `machine/MachineHub.java`
- `sidepanel/network/MachineCollectPacket.java`
- `sidepanel/network/OpenBoundMachineGuiPacket.java`
- `sidepanel/network/ReturnToRSPacket.java`
- `sidepanel/client/MachineFavoritesClient.java`

**具体改动**：

- 绑定数据从 `AltarBinding.RS_NETWORK`/坐标专用字段扩展为 `backendId + networkId`。
- `BindingCache`、机器状态包和收藏键保留机器身份，同时携带后端。
- `OpenBoundMachineGuiPacket` 拆出“打开 GUI”和“从网络取材料/燃料”两个服务，后者调用 `StorageSession`。
- `ReturnToRSPacket` 改成通用 `ReturnToStoragePacket`；RS 返回逻辑留在 RS adapter，BD 返回逻辑走 BD 终端或 RSI fallback。
- `MachineHub` 的客户端渲染可以大部分保留，文案和网络名改为动态字段。

**完成判据**：机器绑定、列表、收藏、状态、远程 GUI、收取产物、标签投料在 RS-only/BD-only 均通过；RS+BD 两台机器分别绑定不同后端时互不串库。

### 16.7 阶段 6：侧栏、容器转移和背包网络升级

**主要文件**：

- `sidepanel/RSSidePanelModule.java`
- `sidepanel/RSSidePanelNetworkHandler.java`
- `sidepanel/RSSidePanelRequestPacket.java`
- `sidepanel/RSSidePanelClickPacket.java`
- `sidepanel/RSSidePanelSyncPacket.java`
- `sidepanel/RSSidePanelDeltaPacket.java`
- `sidepanel/RSInventoryTransferPacket.java`
- `transfer/ContainerTransferLogic.java`
- `transfer/ContainerTransferNetworkHandler.java`
- `mods/sophisticatedbackpacks/RSMagnetUpgradeItem.java`
- `mods/sophisticatedbackpacks/RSPickupUpgradeItem.java`
- `mods/sophisticatedbackpacks/RSFeedingUpgradeItem.java`
- `mods/sophisticatedbackpacks/RSRefillUpgradeItem.java`
- `mixin/sophisticatedbackpacks/*UpgradeWrapperMixin.java`
- `util/BackpackRSUtils.java`

**具体改动**：

- `RSSidePanel*` 可以保留网络包名以兼容协议，但 payload 改为通用快照和 `StorageReference`。
- `ContainerTransferLogic` 的网络目标从 RS 单例改为当前会话，并把自身网络保护改为后端比较。
- 四种背包升级共用 `BackpackStorageUtils`，每件升级自己的 NBT 保存后端引用。
- `RSMagnetUpgradeItem` 保留注册 ID，新增 BD 绑定入口和旧 `RSBlockPos/RSBlockDimension` 读取迁移。
- `MagnetUpgradeWrapperMixin` 只保留过滤/拾取 hook，所有存取转到通用服务。

**完成判据**：侧栏整组/单个提取、拖动分配、`F/G` 转移、世界选取、磁铁/拾取/喂食/补货升级均在 BD-only 实际操作 BD 网络。

### 16.8 阶段 7：一键吃、补货、神化和 FTB 工作流

**主要文件**：

- `autoeat/AutoEatEngine.java`
- `autoeat/client/AutoEatClientEvents.java`
- `anvilmemory/AnvilMemoryRequestPacket.java`
- `enchanting/EnchantingRestockRequestPacket.java`
- `reforging/ReforgingRestockRequestPacket.java`
- `villager/VillagerRestockRequestPacket.java`
- `mods/apotheosis/ApotheosisLibraryService.java`
- `mods/apotheosis/ApothSpawnerUpgradeService.java`
- `mods/apotheosis/ApothSpawnerInteractionHandler.java`
- `compat/ftbquests/ExternalItemProgressBridge.java`
- `compat/ftbquests/FtbQuestSubmissionPlanner.java`
- `compat/ftbquests/FtbQuestSubmissionExecutor.java`
- `compat/ftbquests/QuestSubmissionEscrow.java`

**关键改动**：

- `AutoEatEngine` 删除 `INetworkAwareGrid/GridContainerMenu/GridType` 作为硬前置，改为“从菜单取得会话；否则使用玩家已选后端”。
- 一键吃的食物选择使用 `StorageSnapshot`，费用和容器返还使用同一 session。
- 各补货 packet 不再直接检查 RS `Permission`，统一调用 `canExtract`。
- Apotheosis 图书馆/刷怪笼的扫描、预览、执行、退款全部携带 `StorageReference`。
- FTB 进度桥接只接受通用实际插入事件；`RS` 字样改为动态后端名。

**完成判据**：三种安装组合中，一键吃三模式、补货、图书馆导入、刷怪笼升级、FTB 提交均有成功/部分失败/退款路径。

### 16.9 阶段 8：可选 RS UI、共振和最终隔离

**保留在 RS 模块**：

- `mixin/refinedstorage/*`
- `mods/rs/*`
- `sidepanel/client/GuiNavStack.java` 中只识别 RS Screen 的分支
- `resonance/*`

**处理规则**：

- RS Grid 搜索、历史、滑动提取、原生配方消息只在 RS 存在时注册。
- BD Terminal 提供 P1 等价入口，但不能让 core 依赖 BD 客户端类。
- 共振磁盘先明确标为 RS-only；若要双后端，另立“共振核心”设计，不在后端适配提交中偷偷替换。
- 最后才修改 `mods.toml`：RS/BD 都为可选，并加入至少一个后端的运行时诊断。

**完成判据**：四种启动矩阵均通过；无 RS 时没有 `NoClassDefFoundError`、Mixin target error 或客户端注册崩溃；core 静态检查只允许 RS/BD adapter 白名单。

### 16.10 推荐提交顺序

建议不要按“所有文件一起改”提交，而按以下顺序保留可回退节点：

```text
1. baseline-tests
2. storage-contract
3. refinedstorage-adapter
4. crafting-core-session
5. machine-delegates-vanilla
6. machine-delegates-mods
7. beyonddimensions-adapter
8. binding-and-machine-hub
9. sidepanel-and-transfer
10. backpack-network-upgrades
11. autoeat-and-restock
12. apotheosis-and-ftbquests
13. optional-rs-isolation
14. dual-backend-release-gate
```

每个提交至少执行：

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat compileJava --no-daemon
git diff --check
```

涉及 RS/BD 真实运行时的提交额外执行对应客户端和专服冒烟；涉及 Mixin 或可选类加载的提交必须执行 RS-only、BD-only 和 no-backend 启动检查。

### 16.11 阶段级代码量和受影响文件估算

这是用于排期的区间，不是承诺的最终 diff：

| 阶段 | 主要受影响文件 | 预计修改/新增行 |
|---|---:|---:|
| 0 基线 | 10–20 | 500–1,500 |
| 1 契约 | 8–15 | 1,000–2,000 |
| 2 RS 适配 | 15–30 | 2,000–4,000 |
| 3 合成与 delegate | 60–120 | 6,000–12,000 |
| 4 BD 适配 | 10–20 | 1,500–3,000 |
| 5 机器中心 | 20–35 | 2,500–5,000 |
| 6 侧栏/转移/背包 | 25–45 | 3,000–6,000 |
| 7 一键吃/补货/专用工作流 | 25–50 | 3,000–6,000 |
| 8 隔离与发布 | 20–40 | 1,500–3,500 |

阶段之间有重叠，不能简单把上限相加；完整最终 diff 仍预计约 18,000–30,000 行，测试新增约 5,000–10,000 行。最值得先做的是阶段 1–4：它们决定后续功能是复用还是继续复制 RS 分支。

## 17. 实施进度记录

### 17.1 2026-08-22：阶段 1 原型完成，阶段 2 适配器骨架完成

本轮已新增后端无关公共包 `com.huanghuang.rsintegration.storage` 原型。后续 17.2、17.3 的接入前审查发现契约仍需修订，因此这里的“完成”只表示首版代码和基础测试已经落地，不表示已经达到业务接入条件：

- `StorageBackendId`、`StorageReference`、`StoragePermission`
- `StoredItem`、`StorageSnapshot`、`StorageOperationResult`
- `StorageSession`、`StorageBackend`、`StorageBackendRegistry`

已落实的契约约束：

- 公共包不引用 `INetwork`、`DimensionsNet`、`UnifiedStorage`、`ItemStackKey` 或 `KeyAmount`。
- 库存快照数量使用 `long`，精确物品身份保留 ItemStack NBT，并对可变栈做防御性复制。
- 插入结果返回真实余量和实际接收量；提取结果允许返回多个精确 NBT 分片。
- 所有权限判断显式携带 `ServerPlayer`。
- 注册表可以同时暴露多个可用后端，不在底层隐式决定 RS/BD 优先级。

已新增 `storage/rs` 下的 RS 适配器骨架：

- `RefinedStorageBackend`：玩家网络和持久化引用解析。
- `RefinedStorageSession`：快照、权限、精确/Ingredient 提取、插入和 tracker 记录。
- `RefinedStorageReference`：RS 控制器维度与坐标的内部编码；RS 原生类型不离开适配器包。

已通过的验证：

- `StorageReferenceTest`
- `StorageOperationResultTest`
- `StorageSnapshotTest`
- `StorageBackendRegistryTest`
- storage 定向测试、完整 `test`、`compileJava`、`git diff --check`

当前明确未完成：

- 现有业务调用点仍以 `INetwork` 为主，尚未切到 `StorageSession`。
- RS 适配器尚未注册到统一生命周期；RS 仍是强制依赖。
- BD 适配器尚未加入 RSI 构建和实现。
- RS-only、BD-only、双后端及 no-backend 启动矩阵尚不能执行。

下一批按以下顺序推进：

1. 将 `TrackedNetworkInsertion`、`MaterialSources` 和 `ExtractionLedger` 的存取入口迁到通用会话，同时保留旧 RS facade。
2. 为 RS 适配器增加可替换 native-operation 夹具，锁定 simulate/perform、权限、NBT 分片和退款语义。
3. 完成递归规划与执行核心迁移后，再接入 BD 1.20.1 API；在此之前不修改 `mods.toml`。

### 17.2 2026-08-22：接入前契约审查与修订

在迁移任何业务调用点前，对阶段 1 原型进行了第二轮限制审查。审查确认原模型不能安全表达 BD 的 `item + tag + Forge capabilities` 身份，且空结果无法区分缺货、拒绝、权限不足和后端异常，因此暂停接入并完成以下修订：

- 新增 `StorageItemKey`，以 `backendId + 完整身份 NBT` 作为精确键，展示用 `ItemStack` 不再是权威身份。
- `StoredItem` 和 `StorageSnapshot.countExact` 改为按 `StorageItemKey` 计数；快照增加可选 revision，列表不再反复深复制不可变条目。
- `StorageOperationResult` 增加结构化状态，并将有效提取栈、插入余量和异常恢复栈分开，错误身份不能计入成功转移量。
- 新增 `StorageSnapshotResult`；读取失败不再伪装为空库存。
- 新增 `StorageResolutionResult`；后端缺失、非法引用、网络不存在、区块未加载、权限拒绝和异常可分别表达。
- 新增版本化 `StorageReferenceCodec`，并限制 backend/network 标识长度。
- 新增 `StorageBackendLoader`、provider 和纯字符串 descriptor。未确认可选 mod 存在前不会加载对应适配器类。
- 注册表隔离后端的运行时异常和链接错误，并保留每个后端的解析结果，不让一个坏后端阻断另一个后端。
- RS 控制器引用增加 `v1` 格式前缀；RS 适配器公开 API 不再暴露 `INetwork`。
- RS 精确提取从权威身份载荷重建请求，并按完整序列化身份验证返回物，覆盖 Forge capabilities。

新增测试覆盖精确身份隔离、结构化失败、恢复栈、引用编解码、缺失 mod 时禁止 provider 类加载，以及核心 storage 字节码无 RS/BD 链接。此时仍未注册后端、未迁移业务调用点、未修改强制依赖。

接入前剩余门槛：把 RS 原生调用抽成可替换 driver，补 simulate/perform 竞争、错误身份、部分提取、tracker 顺序和异常后已提取物的 adapter 级测试。完成该夹具前，不迁移 `MaterialSources`、`ExtractionLedger` 或其他功能。

### 17.3 2026-08-22：第三轮遗漏与限制审查

本轮继续保持只读审查：未迁移业务调用点，未实现 BD adapter，未注册 RS adapter，未修改 `mods.toml`。审查范围覆盖 storage 公共包、RS adapter 骨架、BD 1.20.1 的物品键/网络/统一存储实现，以及 RSI 现有所有直接 RS 使用点。

#### 17.3.1 阻断级问题

| 级别 | 问题 | 代码证据 | 可能后果 | 接入前要求 |
|---|---|---|---|---|
| P0 | 后端身份等价规则无法由 `StorageItemKey` 表达 | `StorageItemKey.equals/hashCode` 直接使用 `CompoundTag.equals/hashCode`；BD `ItemStackKey.isSameTypeSameComponents` 使用 canonical bytes，失败时回退 `NbtEq.equalsRelaxed` | NaN 的不同位型、`-0.0/+0.0` 等 BD 认为相同的键会在 RSI 中分裂，导致快照计数、精确提取、预留和退款对账错误 | 把“可重建的后端载荷”和“不可变规范身份”拆开；键的 equals/hashCode 只使用 backend 提供的 canonical identity bytes，并补 NaN、signed zero、tag/caps 顺序测试 |
| P0 | 异常后的实际转移量可能未知，但结果模型强制写成 0 | `RefinedStorageSession.insert` 捕获异常后调用 `failedInsert(input, FAILED)`；提取异常也只能报告已收到的返回栈 | 后端可能已完成部分/全部 mutation 后才抛异常；调用方按“0 转移”重试或退款可复制物品，反向也可能丢物品 | 增加 `INDETERMINATE`/unknown transfer 语义，禁止把未知状态当成可重试；native driver 必须测试 perform 后抛异常、tracker 抛异常和部分 mutation |
| P0 | 一个后端只能为玩家返回一个网络 | `StorageBackend.resolveForPlayer` 返回单个 `StorageResolutionResult`；registry 的 `resolveAllForPlayer` 实际最多每个 backend 一个 session；BD 提供 `getAllNetFromPlayer` | BD 多网络成员只能看到 primary 网络；绑定、机器中心、侧栏和一键吃无法可靠选择目标，双后端时也容易误用默认库 | 增加网络发现结果/descriptor 列表及显式选择策略；“默认网络解析”和“枚举可访问网络”分成两个 API |
| P0 | 活网络调用没有线程约束 | `StorageSession` 所有方法都能从任意线程调用，接口注释未规定 server thread；RS/BD adapter 都将访问活网络或 SavedData | 异步递归规划若误持 session，会跨线程读取或修改 Forge/RS/BD 状态，产生竞态或存档损坏 | 明确 session 仅 server thread；异步层只接收不可变快照和纯规划数据；加入线程断言或统一调度入口 |

#### 17.3.2 高优先级限制

1. **权限异常未被结构化隔离。** `RefinedStorageSession.snapshotItems`、`extractExact`、`extractMatching`、`insert` 都在进入 `try` 前调用 `hasPermission`；而 `hasPermission` 自身直接调用 RS security manager。安全扩展或链接错误会穿透 `StorageSession`，违反“返回结构化失败”的契约。权限检查必须进入 adapter 的异常边界，并且 `hasPermission` 最好也返回带状态的结果，而不是无法区分 denied/unavailable/failed 的 boolean。

2. **BD 权限只能表达成员访问，不能伪造 RS 的 INSERT/EXTRACT 分权。** 通过持久化 network ID 调用 `DimensionsNet.getNetFromId` 后，adapter 必须逐次验证 `net.getPlayers().contains(player.getUUID())`；owner/manager/member 是管理级别，不等于 RS 的 INSERT/EXTRACT 权限。首期应将合法成员映射为 VIEW/INSERT/EXTRACT 全部允许，并在能力描述中明确“无细粒度权限”，而不是根据 manager 身份擅自限制普通成员。

3. **RS 的 `int` 原生数量与公共 `long` 契约仍不完整。** `extractIdentity` 将请求切成 `int`，但 simulate 第一次调用后立即退出，因此大于 `Integer.MAX_VALUE` 的模拟必然被低报。需要规定每个后端的单操作上限，或让模拟返回“至少/上限/不支持完整 long”的明确信息；不能让业务层把部分模拟当成完整可用量。

4. **缺少 batch reservation/commit 能力边界。** 现有 `ExtractionLedger` 是三阶段的多来源预留、预检查、逐项执行和反向退款；单条 `extractExact` 并不提供原子性。通用层必须明确：基础后端仅保证单操作，ledger 负责 best-effort 补偿；若后端能提供 revision 或批量事务，则作为可选能力使用。否则不能宣传“跨多种材料原子提取”。

5. **RS adapter 没有可替换 native driver，现有测试只覆盖数据模型。** 当前测试运行时故意不加载 RS，无法覆盖错误返回身份、simulate/perform 竞争、部分提取、tracker 顺序、权限异常和 mutation 后异常。在建立 driver/port 及 adapter contract tests 前，RS 骨架不能视为已验证实现。

6. **RS 持久引用是易失坐标。** `RefinedStorageReference.fromNetwork` 假定 `network.getLevel()` 和控制器位置始终存在；构造 session 时即可抛异常。控制器移动/重建后坐标引用会陈旧。解析层需要把“暂时无控制器位置”“坐标已失效”“网络已迁移”分开，并为旧绑定提供重新绑定策略。

#### 17.3.3 能力模型遗漏

当前接口只覆盖 item snapshot、精确/Ingredient 提取、插入和三种权限。它足以作为最低物品存储面，但不能承载现有 RSI 的全部 RS 联动语义：

| 能力 | 当前 RSI 用途 | 处理结论 |
|---|---|---|
| 物品 tracker 时间戳/操作者 | 侧栏排序、变化时间、外部插入记录 | 定义可选 `StorageChangeTrackingCapability`；RS 实现原生 tracker，BD 若无等价 API则返回不支持并使用稳定 fallback 排序 |
| RS crafting manager 状态 | 侧栏展示正在合成/可合成信息 | 定义 RS 专属或可选 crafting-status capability；不得塞入基础 item session，也不得让 BD 假装支持 |
| 快照 revision/变更订阅 | 异步规划校验、增量侧栏同步 | 基础快照允许 unknown revision；可选 revision/subscription capability，执行前仍需重新校验 |
| 网络显示名与可选择网络列表 | BD 多网络、双后端选择、机器绑定 | 增加 backend/network descriptor，不让 UI 通过解析 opaque `networkId` 猜名字 |
| 操作诊断 | 区分权限、后端 hook 拒绝、异常、错误响应 | 结果增加稳定 diagnostic code/correlation id；详细异常只写限流日志，不把 native exception 暴露给业务/网络包 |
| 资源类型 | 当前目标主要是物品，但 BD 还支持流体、能量等 | 明确 `ITEM_STORAGE` capability；首期不承诺其他资源，未来扩展不能破坏 item key 契约 |

#### 17.3.4 现有 RSI 调用面的复核结果

静态扫描得到 **142 个** storage 包之外直接引用 RS 类型、`INetwork` 或 `RSIntegrationNetwork` 的 Java 文件，分布以 `mods`（62）、`mixin`（25）、`crafting`（11）、`sidepanel`（10）、`resonance`（8）和 `network`（6）为主。这说明后续迁移不能只替换 `MaterialSources` 和 `ExtractionLedger`：

- 合成和各机器 delegate 依赖枚举、模拟、精确提取、三阶段预留、来源保持、失败反向退款和 tracker 顺序。
- 机器管理中心/侧栏除物品存取外，还依赖 RS crafting manager、tracker 时间戳、Grid 菜单和返回终端导航。
- 一键吃、四类补货、Apotheosis、FTB Quests、背包升级依赖会话来源、权限和容器返还；同一次工作流必须固定同一个 `StorageReference`，不能中途重新解析默认后端。
- `resonance` 和 `mixin/refinedstorage` 包含真正的 RS 原生 UI/磁盘/网格语义，应继续隔离为 RS-only，而不是强行映射到 BD。

#### 17.3.5 接入闸门与最小修订批次

因此阶段 1 当前状态调整为 **原型已落地、契约未冻结**。在以下项目全部完成前，继续禁止迁移 `MaterialSources`、`ExtractionLedger`、机器中心、一键吃或其他业务功能：

1. 重做 `StorageItemKey`：canonical identity、后端载荷、展示栈三者分离，锁定 equals/hashCode 契约。
2. 增加网络发现 API，支持同一 backend 的多个网络以及显式默认网络。
3. 明确 server-thread-only live session；异步只传 immutable snapshot。
4. 拆出可替换 native driver，补 RS adapter 的异常、竞争、部分结果和 tracker 测试。
5. 修正权限异常边界，增加 indeterminate operation 和有界诊断信息。
6. 建立 optional capability 查询，至少覆盖 item storage、change tracking、crafting status、revision/subscription。
7. 写出 ledger 的跨来源预留/补偿契约测试，明确“不保证跨条目原子性”的产品边界。

完成这批后先再做一次静态和测试审查；只有审查通过，才开始第一个业务 facade 迁移。BD adapter 仍排在通用契约和 RS driver 稳定之后。

### 17.4 2026-08-22：第一批接入前契约修复

根据 17.3 的阻断项完成第一批修复。本轮仍未迁移任何业务调用点，未实现 BD adapter，未注册后端，未修改 `mods.toml`。

已完成：

- `StorageItemKey` 将 backend payload、canonical identity bytes 和 display stack 分离。equals/hashCode 只依赖 `backendId + canonical bytes`；默认 ItemStack 路径使用 key 顺序稳定的确定性 NBT 编码，未来 BD adapter 可直接传入 BD relaxed canonical bytes。
- `StorageSnapshot` 在构造时按 canonical key 聚合重复条目，避免 Ingredient 模拟对同一身份重复计数。
- 新增 `StorageNetworkDescriptor`、`StorageDiscoveryResult` 和 backend discovery 汇总。默认网络解析与全部可选网络发现不再混为一谈；旧 `resolveAllForPlayer` 标记 deprecated，并明确它只表示每个后端的默认 session。
- 新增 `StorageThreadGuard`；`StorageSession` 契约明确 live handle 仅能在 Minecraft server thread 使用，异步规划只允许保留 immutable snapshot 和 `StorageReference`。
- 权限从 boolean 主契约升级为 `StoragePermissionResult`，区分 allowed、denied、unavailable、failed；RS security manager 异常现在被 adapter 边界捕获。
- 操作结果新增 `INDETERMINATE`、`transferKnown`、`remainderKnown` 和稳定 diagnostic code。进入 native PERFORM 后抛异常不再谎报为零转移，也不会提供看似可安全退款的完整余量。
- `RefinedStorageSession` 不再直接持有 `INetwork`，改为依赖无 RS 类型的 `RefinedStorageDriver`；只有 `NativeRefinedStorageDriver`、RS backend 和引用 codec 接触原生 RS API。
- 新增纯 `RefinedStorageOperationExecutor`，锁定 simulate -> tracker -> perform 顺序，并覆盖 tracker 异常、perform 异常、部分提取、错误身份和回滚异常。
- 新增 `StorageCapability`。当前 RS 骨架只声明 `ITEM_STORAGE`；change tracking、crafting status、snapshot revision 和 change subscription 在没有实际扩展接口前不会被伪报为支持。

验证结果：

- storage/RS executor 定向测试 35 项通过。
- 完整项目 `test --no-daemon` 通过。
- `compileJava` 由完整测试构建执行并通过。
- `git diff --check` 通过。
- 核心 storage 类型字节码不链接 RS/BD；可测试的 RS session/driver/executor 字节码不链接 native RS API。
- `javap -public` 确认 `RefinedStorageBackend` 和 `RefinedStorageSession` 的公开/实现接口未暴露 `INetwork`。

仍未解除的接入闸门：

1. BD adapter 尚未实现，因此 BD `ItemStackKey` 的 canonical bytes、payload 重建和成员校验还没有 adapter 级测试。
2. RS driver 已可单测，但真实 RS runtime 下的 security extension、控制器失效、tracker 和存储 hook 仍需 GameTest/专服冒烟。
3. `ExtractionLedger` 的跨网络/背包/玩家库存预留与 best-effort 补偿尚未迁移成后端无关契约；目前不能开始合成核心切换。
4. capability 目前只有声明模型；tracker/crafting/revision 的扩展操作接口要按实际消费者分别设计，不能先做空实现。
5. structured diagnostic 已有稳定 code，但限流日志和 correlation id 尚未接入统一服务。

下一批应先实现独立的 BD driver/adapter 测试夹具以及 ledger 补偿契约，不直接改 `MaterialSources`、机器中心或一键吃。

### 17.5 2026-08-22：第四轮契约复查与加固

本轮仍只修 storage 基础层和 RS adapter 边界：未实现 BD adapter，未迁移递归合成、次元磁铁、机器管理中心、一键吃或其他业务调用点，未注册统一后端，也未修改 `mods.toml`。

第四轮复查发现 17.4 的几个接口仍允许调用方把“不知道”误当成“空/零”，同时 custom canonical identity 少了物品类型。现已完成以下修复；本节中的新契约覆盖 17.4 的对应旧描述：

- `StorageItemKey.equals/hashCode` 改为 `backendId + 稳定物品注册 ID + canonical component bytes`。物品注册 ID 从 display/native key 中提取，未注册物品直接拒绝；因此 BD 的 canonical bytes 即使只包含 tag/capabilities，也不会把不同物品合并。
- `StorageOperationResult.transferredAmount()` 改为 `OptionalLong`，`remainder()` 改为 `Optional<ItemStack>`，移除公开的 `-1` 和空栈哨兵以及容易漏检的 known boolean。mutation 后异常必须由调用方显式处理 unknown。
- `StorageSnapshotResult.snapshot()` 改为 `Optional<StorageSnapshot>`；失败结果不再携带 `StorageSnapshot.EMPTY`，不能被误当成成功的空库存。
- 快照状态从通用 `StorageOperationStatus` 拆为 `StorageSnapshotStatus`，只允许 SUCCESS、DENIED、UNAVAILABLE、INVALID_RESPONSE、FAILED，避免读取结果出现 NOT_FOUND、REJECTED、INDETERMINATE 等无关状态。
- `StorageDiscoveryResult.success` 现在拒绝 null descriptor、重复 reference、多个默认网络和混合 backend ID。registry 还会验证 descriptor 的 backend ID 等于实际注册 backend，畸形 adapter 只得到结构化 FAILED。
- registry 同样验证 resolved session 的 reference 属于被调用的 backend，防止错误 adapter 返回跨后端会话。
- RS backend ID 移到不链接 RS API 的 `RefinedStorageIds`；可测试的 session/driver/executor 不再间接引用 `RefinedStorageBackend`。
- `RefinedStorageBackend.resolve` 将引用解析、level 获取、区块加载检查、网络解析和 session 创建全部纳入结构化异常边界。
- 纯 simulate 插入遇到错误 native remainder 时，统一返回 INVALID_RESPONSE + INVALID_NATIVE_RESPONSE，不再降级成普通 BACKEND_EXCEPTION。
- RS 插入仍按现有语义执行 `simulate -> tracker -> perform`。tracker 记录的是 preflight accepted amount，只用于 RS 变化跟踪，不是结算依据；最终 `StorageOperationResult` 仅按 perform 返回值计算。若 hook 使 simulate 与 perform 数量不同，tracker 元数据可能不精确，因此后续任何账本和补偿逻辑禁止依赖 tracker 数量。

新增回归覆盖：不同物品共享相同 canonical component bytes、unknown transfer/remainder、失败快照无值、重复/多默认/跨后端 discovery、跨后端 resolution、RS class-reference 隔离，以及模拟插入的非法 native response 分类。storage/RS 定向测试现为 **41 项通过**。

验证同时通过完整 `test --no-daemon`、编译、`git diff --check`、核心/RS 字节码隔离测试和 `javap -public` 公共签名检查；公开 RS adapter 签名没有暴露 `INetwork`，旧的 known boolean、`-1` 哨兵和失败快照空值访问器均已从 storage 调用面清除。

本轮后仍未解除接入闸门：

1. 需要继续审查 operation result 的跨多条目补偿语义，并为后端无关 `ExtractionLedger` 写纯契约测试；尚不迁移真实合成业务。
2. RS session 的 server-thread/权限路径因 `ServerPlayer` 与真实 server 依赖，当前只有 driver/executor 纯测试；仍需 GameTest 或专服冒烟。
3. BD adapter 尚未开始。其实现前必须再次核对 1.20.1 `ItemStackKey` payload 重建、membership、primary/all network 和 long quantity 行为。
4. RS tracker/crafting manager、snapshot revision/subscription 仍只是明确的可选能力边界，没有通用扩展接口；机器中心和侧栏暂不能迁移。
5. 在下一轮静态审查和 ledger 契约测试通过前，不开始递归合成、次元磁铁、一键吃或机器管理中心接入。

### 17.6 2026-08-22：第五轮基础层复查与修复

本轮继续限制在 storage 公共契约和 RS adapter 边界内。未实现 BD adapter，未注册统一后端，未修改 `mods.toml`，也未迁移递归合成、次元磁铁、机器管理中心、一键吃或任何现有业务调用点。

本轮修复如下：

- registry 现在统一执行 server-thread guard；显式解析必须返回与请求完全相同的 `StorageReference`，同一 backend 下返回错误 network 也会得到 `INVALID_REFERENCE`。默认解析仍只校验 backend 归属，因为默认网络本来就由 adapter 选择。
- 默认 discovery 使用 session 的真实 capability 集合，不再硬编码只有 `ITEM_STORAGE`；后端可用性、解析和发现异常继续按后端隔离。
- `StorageOperationResult` 的 insert/extract 专属字段现在按 kind 拒绝错误访问；indeterminate 必须有诊断码。插入结果新增后端身份比较入口，因此未来 BD 可使用自己的 canonical equality 校验余量，同时通用层仍强制物品类型相同，并用副本调用比较器以防比较器修改数量。
- `StoragePermissionResult` 拒绝状态与诊断码矛盾的组合。RS 的 VIEW 不再无条件允许，而是由 INSERT、EXTRACT、AUTOCRAFTING 三项原生权限组合得出。
- `StorageSnapshotResult` 现在也携带稳定诊断码。权限检查失败、backend exception、null/malformed native snapshot 的原因会继续传到 extraction result，不再退化为无原因的 `FAILED`。
- 新增严格的显式 RS 网络解析路径。旧 `resolveNetwork` 保留 catch-and-null 兼容行为；storage adapter 使用 `resolveNetworkStrict`，反射调用失败不会伪装成网络不存在。
- RS insert 的 null native response 按 mutation 阶段区分：simulate/preflight 为 `INVALID_RESPONSE`，进入 perform 后为 `INDETERMINATE`。tracker、应用观察器和 native perform 的失败分别使用独立诊断码。
- 插入观察器在每次真实 perform 尝试前运行，即使 preflight 估算接收量为 0，也能覆盖 simulate/perform 之间状态变化；tracker 仍只在 preflight 接收量大于 0 时记录。RS 观察器保留现有 `MaterialSources.invalidateFor(player)` 和菜单广播行为，并留在 RS-only 包内。
- 原版/RS canonical identity 继续折叠 `+0.0/-0.0` 并拒绝 NaN；测试同时确认 1.20.1 原版会折叠不同声明元素类型的空 NBT list，编码器保持同样规则。BD 未来仍可通过 custom canonical bytes 表达自己的 relaxed equality。
- `RefinedStorageSnapshotRead` 现在深复制输入和输出的 `ItemStack`，不再把可变 native 快照栈泄漏给 mapper 或测试夹具。optional loader 也会隔离 mod-presence predicate 和类加载阶段的 runtime/linkage failure。
- 修复 `RefinedStorageSession` 遗漏的 `StoredItem` 导入；该遗漏曾导致定向构建直接编译失败，现已由完整构建覆盖。

本轮验证结果：

- storage/RS 定向测试 **57 项全部通过**。
- 完整 `test --no-daemon` 通过，包含现有项目全套测试。
- `git diff --check` 通过，仅报告仓库既有的 LF/CRLF 转换提示，没有空白错误。
- 核心 storage 源码和字节码不链接 RS/BD 类型；可测试的 RS session/driver/executor 不链接原生 RS API。
- `javap -public` 确认公共 storage 结果签名不含 RS 类型，`RefinedStorageBackend` 的公开接口也未暴露 `INetwork`。
- `mods.toml` 无 diff；当前仍是原项目的 RS 强依赖发布形态。

本轮后接入闸门仍然存在：

1. 后端无关 ledger 的 reservation、commit、partial settlement、indeterminate 和 best-effort compensation 契约尚未建立；这是开始递归合成迁移前的下一项基础工作。
2. RS adapter 仍未注册到 mod 生命周期，真实 RS runtime 的 security extension、控制器失效、tracker 和存储 hook 仍需 GameTest 或专服冒烟。
3. BD adapter 尚未开始；BD 1.20.1 的 canonical payload 重建、membership、primary/all-network 和 long quantity 行为仍须在实现前再次逐项核对。
4. change tracking、crafting status、revision/subscription 仍只有 capability 边界，没有通用扩展接口；机器中心和侧栏不能据此开始迁移。
5. 因此当前结论仍是“基础层已进一步加固，但契约尚未冻结”。下一阶段不能直接接入递归合成、次元磁铁、一键吃、机器中心或其他业务代码。

### 17.7 2026-08-22：后端无关结算账本与第六轮收尾审计

本轮继续遵守接入冻结：没有实现 BD adapter，没有注册 RS adapter，没有修改 `mods.toml`，也没有替换旧 `crafting.ExtractionLedger` 或迁移递归合成、次元磁铁、机器管理中心、一键吃及其他业务调用点。新增代码只是可供后续编排层使用的纯结算模型，不会自行访问或修改任一存储网络、玩家背包或世界物品。

本轮完成：

- 新增 `StorageReservationSource`，明确区分存储网络、玩家背包和外部来源。网络来源必须携带 `StorageReference`，并在预留时校验其 backend 与 `StorageItemKey` 一致；本地来源只接受有界、非空的稳定 ID。
- 新增 `StorageSettlementLedger`，覆盖 `OPEN -> COMMITTING -> COMMITTED/RECOVERY_REQUIRED/INDETERMINATE -> RECOVERING -> ROLLED_BACK/INDETERMINATE`，以及成功消费后的 `SETTLED`。该类只记录事实，不调用 `StorageSession`、RS `INetwork`、`ServerPlayer` 或任何 native API，并明确要求由单一编排线程持有。
- 预留条目和恢复资产均使用账本 UUID 加单调序号作为 ID。另一个账本中数值相同的 ID、混入本账本 token 的外部 ID以及归属其他条目的恢复资产都会在任何状态变更前被拒绝；分组 settlement 先整体验证，再统一改变状态。
- commit 只接受 `PERFORM` 的 extraction result。仅当状态为 `SUCCESS` 且已知转移量等于预留量时才视为 committed；部分结果、失败状态或数量不符进入恢复，未知 mutation 永远保持 `INDETERMINATE`，即使所有已确认物理碎片后来都已返还，也不会被伪报成完整 rollback。
- 每个 confirmed extracted stack 和 native `recoveryStacks()` 都转换成独立的 opaque recovery asset。后者即使物品身份异常或无法生成合法 canonical key，仍可按原物理栈返还；账本不会为了满足键模型而吞掉未知资产。所有公开栈和快照均防御性复制。
- recovery 支持部分返还和多轮重试；只有所有已确认资产都已返还且不存在未知 mutation，账本才进入 `ROLLED_BACK`。模拟插入、错误 result kind、超过剩余资产的数量以及跨条目/跨账本 asset ID 都会 fail closed。
- 新增 `StorageOperationMode.SIMULATE/PERFORM` 并纳入 `StorageOperationResult`。RS executor/session 会准确传播调用模式，防止预检结果误入真实结算。为兼容当前内部调用，未带 mode 的旧工厂仍默认 `PERFORM`；后续迁移新调用点时应优先显式传 mode，并在契约冻结时再决定是否废弃默认重载。

第六轮只读复核还确认一项必须由未来编排层承担的约束：`recordRecovery(entryId, assetId, result)` 可信任调用方传入的 result 确实来自对该 recovery asset 的插入尝试，因为通用 insert result 只表达数量、状态和余量，不携带原输入资产 ID。接入业务时必须在同一同步调用栈内以 `RecoveryAsset` 发起插入并立即以对应 ID 记账，不能缓存、重排或把另一个栈的结果套到该 ID 上。账本提供的是一致性校验和事实记录，不是跨存储系统的事务管理器。

验证结果：

- storage/RS 定向范围共 **77 项测试全部通过**，其中新增账本用例覆盖独立 token 结算、部分/失败/未知提取、可重试补偿、未知退款、模拟结果拒绝、跨 backend 来源、跨账本 ID、原子 token 校验、异常 recovery stack 和防御性复制。
- 完整 `test --no-daemon` 共 **1105 项测试全部通过**，0 failure、0 error、0 skipped；`compileJava` 和 `compileTestJava` 同时通过。
- `javap -public` 确认新账本、来源和 operation result 的公开签名不暴露 RS/BD native 类型。
- 核心 storage 源码隔离扫描通过；native RS 引用仍只存在于 `storage.rs` 边界。`git diff --check` 无空白错误，只有现有 LF/CRLF 提示；`mods.toml` 仍无 diff。

当前阶段定位：

- 这不是阶段 0。阶段 1 的通用核心模型、结果语义、隔离边界和纯结算账本已经落地并经过多轮审查；阶段 2 的 RS adapter 骨架也已存在，但尚未注册、未经过真实游戏环境冒烟，不能算阶段 2 完成。
- 阶段 1 现在是 **实现基本完成、等待适配器级验证后冻结**。冻结前仍需用真实 BD 1.20.1 行为验证 canonical payload、long quantity、网络成员/多网络选择和返还语义；这不等于现在开始写 BD adapter。
- 旧 `crafting.ExtractionLedger` 仍是线上业务实际使用的账本，新 `StorageSettlementLedger` 目前没有业务调用者。因而当前构建仍然必须安装 RS，用户还不能在只安装 BD 的情况下使用这些功能。

下一步仍应先做接入前审计，而不是直接迁移业务：锁定新账本与未来编排 facade 的调用协议，列出旧 `ExtractionLedger` 每一类来源和失败路径如何映射，再选择一个 blast radius 最小的只读/低风险调用面作为首次迁移。BD adapter、生命周期注册、`mods.toml` 可选依赖和核心功能切换继续留在后续明确阶段。

### 17.8 2026-08-22：第七轮契约审计与四项阻断修复

第七轮只读审计发现四项会阻断阶段 1 冻结的问题，随后仅在 storage 基础层和未注册的 RS adapter 骨架内完成修复。现有业务、旧 `ExtractionLedger`、生命周期和 `mods.toml` 仍未改变。

- `StorageSnapshot` 现在必须显式携带 `StorageBackendId`，构造时拒绝任何其他 backend 的 `StoredItem`。通用无归属的 `StorageSnapshot.EMPTY` 已移除；空快照同样必须声明归属。`StorageSession` 契约明确要求成功快照的 backend 等于 session reference 的 backend。
- RS exact extraction 在调用 native driver 前，会从 reconstruction payload 重新生成 RS canonical key 并与请求 key 完整比较。payload、物品类型或 canonical identity 任一不一致都会返回已知零转移的 `INVALID_REQUEST`，不会触发 native 提取或补偿路径。
- RS Ingredient matching 进入受控异常边界。第三方 Ingredient 的 `isEmpty` 或 `test` 抛出 runtime/linkage failure 时，返回 `FAILED + INGREDIENT_MATCH_FAILED`；异常发生在任何提取前，不会被错误标记为未知 mutation。
- `StorageItemKey` 对 reconstruction payload 和 canonical identity 分别设置 2 MiB 上限，并对 NBT 设置 512 层深度上限。默认 exact identity 和后端自定义 canonical identity 两条构造路径都执行边界检查，避免未来持久化或网络输入造成无界内存分配或递归栈耗尽。

新增回归覆盖混合 backend 快照、空快照归属、payload/key 不一致时 native driver 零调用、抛异常的自定义 Ingredient，以及超深 NBT、超大 payload/canonical byte 数组。storage/RS 范围现为 **81 项测试全部通过**；完整项目现为 **1111 项测试全部通过**，0 failure、0 error、0 skipped。`javap -public`、核心隔离测试和 `git diff --check` 同时通过。

这轮修复仍不会影响当前游戏功能：新 registry/adapter/ledger 没有生命周期注册或业务调用者，RS 仍是发布必需依赖。阶段定位保持为 **阶段 1 实现基本完成、继续审计后再冻结；阶段 2 仅有未注册 RS 骨架**。

### 17.9 2026-08-22：阶段 1 契约冻结

在最后一次有界审计中完成三项收口修复，此后停止无边界的基础接口细化：

- `StorageOperationResult` 统一校验失败状态与诊断码。`FAILED`、`INVALID_RESPONSE` 必须携带诊断；`DENIED`、`INVALID_REQUEST` 不允许伪装成带 backend exception 的结果；`INDETERMINATE` 继续只允许真实 mutation 路径和非空诊断。
- Ingredient 安全匹配从 RS executor 移到 backend-neutral `StorageSnapshot.match`，使用 `SUCCESS / EMPTY_INGREDIENT / FAILED` 结构化结果。RS 和未来 BD adapter 共用同一异常边界，第三方 `isEmpty` 或 `test` 异常不会逃逸。
- 删除所有会隐式选择 `PERFORM` 的 insert/extract/failure 工厂重载。所有可模拟操作现在必须在编译期显式传入 `StorageOperationMode`；只有名称本身表示真实 mutation 未知的 indeterminate 工厂固定为 `PERFORM`。

新增测试覆盖失败诊断矛盾、自定义 Ingredient 的 `isEmpty`/`test` 异常、空 Ingredient 状态和公共嵌套类型隔离。storage/RS 范围现为 **82 项全部通过**；完整项目 **1112 项全部通过**，0 failure、0 error、0 skipped。`javap -public` 确认公开 operation 工厂全部要求 mode，`git diff --check`、核心 optional-type 隔离和 `mods.toml` 无变更检查通过。

自本节起，**阶段 1 通用存储契约冻结**。后续不再以猜测未来需求为由反复修改基础接口；只有 RS/BD adapter 的真实夹具或游戏环境测试提供可复现证据时才允许解冻。下一项工作进入阶段 2：完成并验证 RS adapter 的注册/生命周期边界，然后实现 BD 1.20.1 adapter；业务迁移仍需等两个后端都能通过相同 session 契约后开始。

### 17.10 2026-08-22：阶段 2 第一批，RS adapter 生命周期接线

阶段 2 开始实施。新增 `StorageBackendRuntime` 作为模组进程生命周期内的统一后端持有者，并在 `FMLCommonSetupEvent` 开头通过纯字符串 descriptor 加载 RS provider。相同 backend ID 在同一模组生命周期内最多尝试加载一次，首次成功或失败结果都会保留，避免配置重载、重复事件或后续调用造成重复注册。loader 的 runtime/linkage failure 继续被隔离为结构化结果，错误 backend ID 不能污染 runtime 状态。

RS session 仍然按玩家解析请求临时创建；runtime 只长期持有无网络状态的 `RefinedStorageBackend`，不会缓存 `INetwork`、玩家或服务器对象，因此跨世界退出和同一 JVM 内服务器重启不需要清理陈旧网络引用。本批没有接入 BD、没有修改 `mods.toml`、没有迁移任何业务调用点，现有功能仍走原 RS 路径。

本批已通过 storage/RS 定向测试、完整测试和 `compileJava`。Gradle XML 的最终统计为 storage/RS **81 项通过**，完整项目 **1115 项通过**，0 failure、0 error、0 skipped；`git diff --check` 通过。17.9 的“82 项”是此前按控制台输出手工记录的历史数字，本节起统一以 Gradle XML 汇总为准。真实 RS 客户端/专服冒烟仍是阶段 2 的后续验收项，不能由单元测试替代。

专服冒烟已推进到 Forge 发现 `refinedstorage-1.12.4.jar`、读取 RSI Mixin 配置并准备 18 个当前环境可用的 Mixin；随后 Minecraft 因开发目录尚未接受 EULA 而正常停止，尚未进入 `FMLCommonSetupEvent`，因此不能把这次运行记作 lifecycle 注册成功。EULA 不由自动化代替用户接受。

同时再次核对 `D:\sd\BeyondDimensions`：当前分支为 `1.20.1`，提交为 `6642c5ae2cd87e42db2b0b530b120faead97c6bd`。源码确认网络引用使用整数 ID、玩家可枚举多个成员网络、`UnifiedStorage.insert` 返回余量、`extract` 返回实际提取量，`ItemStackKey` 身份包含 item、tag 和 Forge caps。尝试生成本地 BD jar 时，Gradle 8.14.3 已下载且 Java 17 选择问题已排除，但插件仓库下载 `foojay-resolver-1.0.0.jar` 时 TLS 握手被远端终止；在获得真实编译 jar 前，不提交靠猜测签名的 BD adapter。

### 17.11 2026-08-22：阶段 2 第二批，RS adapter 运行状态与身份语义加固

本批暂停 BD，只针对 RS 1.12.4 的真实 API 和字节码完成适配器加固。`INetwork.canRun()` 已确认同时包含控制器所在位置已加载、能量足够和红石模式允许三个条件，而 RS 的 `insertItem` / `extractItem` 本身不会代替调用者检查该状态。

- `NativeRefinedStorageDriver` 在快照、权限、tracker、插入和提取每次 native 调用前验证 `canRun()`，并通过控制器维度与坐标重新执行严格解析，要求当前位置仍返回同一个 `INetwork` 对象。断电、红石关闭、区块卸载、控制器拆除或同坐标网络重建都会令旧 session 返回 `UNAVAILABLE`，不会继续使用缓存的旧网络对象。
- 新增内部不可用异常，将“门禁在 native mutation 前拒绝”与普通 backend exception 分开。模拟和正式插入、提取在确定尚未调用 RS mutation 时保留已知零转移；只有 native mutation 已经开始后发生的未知异常继续报告 `INDETERMINATE`。
- RS item key 现在以 RS 的 `ItemStack.isSameItemSameTags` 语义构造 canonical identity，即 item + 原生 tag，不把 Forge capabilities 纳入 RS 等价判断；完整 `ItemStack.save` 仍作为 reconstruction payload 保留。快照聚合、请求模板校验和 native 返回栈校验全部使用同一个 RS key mapper。
- `StorageSession.itemKey` 公开后端权威身份映射，`StorageSettlementLedger` 增加接收 session 的重载。后续业务迁移记录 RS 提取结果时必须通过 session mapper，不能退回默认 Forge 完整 payload 等价规则。

新增回归覆盖 RS item+tag 等价、null/空 tag 与原生比较的一致性、模拟与正式操作之间网络失效的已知零结果，以及不可用提取不被误报为 mutation 未知。storage/RS 定向范围全部通过；完整 Gradle XML 统计为 **1119 项通过**，0 failure、0 error、0 skipped；`compileJava` 随全量测试成功，`git diff --check` 通过。

本批没有接入 BD、没有修改 `mods.toml`、没有迁移递归合成、机器管理中心、一键吃、次元磁铁或其他现有业务调用点。当前线上功能仍走旧 RS 路径；新门禁只影响尚未被业务消费的通用 RS adapter。真实专服仍需在用户接受开发目录 EULA 后验证断电、红石关闭、拆除控制器和重建控制器四种场景。

### 17.12 2026-08-22：RS-only 开发专服启动验收

用户接受开发目录 EULA 后，1.20.1 Forge userdev 专服已在仅提供 Refined Storage 的最小后端环境中真实启动。日志确认 RSI 和 RS 完成构造及配置加载，`FMLCommonSetupEvent` 成功注册 `refinedstorage` backend，世界创建及 RecipeCatalog 预热完成，最终到达 `Done (20.853s)`。因此阶段 2 的“RS adapter 能随专服生命周期加载并注册”已经通过，不再只是单元测试推断。

启动验收同时暴露并修复三个与存储契约无关、但会阻断开发专服的生命周期问题：

- 生产混淆版 `refinedstorage-1.12.4.jar` 不能直接放入 Forge userdev 的 `run/mods`；否则会因 Mojang 映射名不匹配触发 `NoSuchMethodError`。`build.gradle` 现在通过 `runtimeOnly fg.deobf("local:refinedstorage:1.12.4")` 提供开发运行时映射后的 RS jar，`run/mods` 中原文件仅改名为 `.disabled`，`libs` 下的发布依赖未删除。
- common 模组主类中的 `DistExecutor.safeRunWhenOn` 会在专服构造期检查并拒绝带 client-only 引用的 referent。客户端事件集中迁入 `ClientEventBootstrap`，按物理端通过 `unsafeRunWhenOn` 延迟调用；专服不再解析这些客户端监听器。
- 模组构造函数不能在 Forge 实际加载 COMMON 配置前调用 `ConfigValue.get()`。配置缓存现在只在对应 `ModConfigEvent.Loading/Reloading` 后刷新；会改变 registry shape 的 Sophisticated Backpacks 物品注册只按模组存在性决定，运行功能开关仍在配置加载后读取。

本次日志中的可选模组 Mixin `ClassNotFoundException` 和 `MarketRegistry not available` 是最小 RS-only 开发环境缺少相应可选模组时的软失败，没有阻止专服到达 `Done`。本次 Gradle TTY 没有把 `stop` 转发到 Minecraft 控制台，最终使用 Ctrl+C 结束已知 `runServer` 会话，因此本轮不宣称正常停服和世界保存流程已验收。

启动修复完成后，全量 Gradle XML 汇总为 **1119 项测试全部通过**，0 failure、0 error、0 skipped；独立 `compileJava --no-daemon` 与 `git diff --check` 同时通过。

本轮仍未创建真实 RS 控制器网络，也未验证真实快照、模拟/正式插入与提取、权限/security extension、断电、红石关闭、区块卸载、控制器拆除或同坐标重建。这些属于阶段 2 后续的游戏内交互验收，不能由“专服到达 Done”替代。BD adapter、`mods.toml` 可选依赖改造和业务迁移仍未开始；当前发布版依然必须安装 RS。

### 17.13 2026-08-22：RS adapter 游戏内验收入口

为避免把现有旧业务路径误当成新 storage adapter 的测试，新增管理员命令 `/rsi_storage_test`。该命令只解析已注册的 `refinedstorage` backend，不引用 BD，也不改变正常功能调用点；命令要求权限等级 2。

- `/rsi_storage_test snapshot`：读取玩家当前 RS 网络的后端无关快照，只报告 item key 数量和 revision。
- `/rsi_storage_test simulate_insert`：使用主手物品执行模拟插入，验证返回状态、接收数量和余量已知性，不改变 RS 库存。
- `/rsi_storage_test roundtrip <1-64>`：将主手物品指定数量真实插入 RS，随后使用同一 session 的后端权威身份立即提取相同数量。插入未完整接收时不会继续提取；该命令不会修改玩家背包，但若用户在执行期间人为断电或拆除网络，可能按结果语义留下待人工检查的网络物品，因此只用于专门测试世界。

本入口编译通过，尚未宣称游戏内操作已通过；必须使用重新打包的 JAR 在真实 RS 控制器网络中执行上述命令，并记录每条命令的聊天输出及 `latest.log`。

### 17.14 2026-08-22：RS adapter 首次真实存取通过

在植物科技实例的真实 RS 网络中执行了本入口，日志确认：

- `RS snapshot OK: 147 item keys, revision=-1`：真实快照成功。
- `RS simulate insert: status=SUCCESS, accepted=64, remainderKnown=true`：64 个物品模拟插入成功，库存未因模拟操作改变。
- 两次 `RS roundtrip: inserted=1, extracted=1, extractStatus=SUCCESS`：真实插入和提取均准确完成，后端身份映射和数量结算一致。
- 客户端随后正常保存所有维度并退出；没有 RSI 自身的 ERROR、异常或 `INDETERMINATE` 结果。

因此，RS adapter 的“快照、模拟插入、正式插入/提取”基础通路已通过真实游戏验收，可以开始**RS-only 的业务接入**。本结论不代表断电、红石关闭、区块卸载、控制器拆除/重建等故障场景已经验收；这些场景仍应在业务接入前后作为回归测试保留。BD 仍未接入。

### 17.15 2026-08-22：次元磁铁 RS-only 迁移第一批

次元磁铁的绑定动作保持不变：潜行右键 RS 控制器仍将维度和坐标写入升级物品。磁铁 Mixin 的两条实际存取路径已经改为调用新 `StorageSession`：地面物品进入网络走 `session.insert(..., false)`，背包吸取网络物品按原有模拟/正式标志走 `session.insert(..., simulate)`。网络引用在每次操作时按绑定坐标重新解析，控制器失效、区块未加载、权限不足或 backend 不可用时不会继续使用旧 `INetwork`。

本批只迁移 RS 磁铁本体，未迁移 Refill、Feeding、Restock 等其他背包升级，未接 BD，未改变绑定 NBT 字段，也未修改现有 RS 以外的业务路径。全量测试通过；真实游戏中的磁铁三条路径仍需使用新 JAR 手动回归。

### 17.16 2026-08-22：拾取、补货、一键吃 RS-only 迁移

在磁铁实测通过后，Pickup、Refill、Feeding 三个背包升级一并切换到 `StorageSession`。Pickup 复用插入结果和剩余物品处理；Refill 与 Feeding 使用绑定网络的精确物品身份提取，并在目标槽位填充失败、食用失败或产生剩余容器时通过 session 原路返还。三者均按绑定坐标在每次操作时重新解析，未接入 BD，未迁移 Restock。

本批已通过编译和全量自动测试；需要在游戏中分别回归拾取、补货、喂食的成功、过滤不匹配、网络不可用和失败返还场景。

## 附录 A：关键代码证据

### RSI

- 强制 RS 依赖：`src/main/resources/META-INF/mods.toml`
- RS 网络解析和存取：`src/main/java/com/huanghuang/rsintegration/network/RSIntegrationNetwork.java`
- 网络物料枚举：`src/main/java/com/huanghuang/rsintegration/crafting/MaterialSources.java`
- 合成上下文直接保存 `INetwork`：`src/main/java/com/huanghuang/rsintegration/crafting/ResolutionContext.java`
- RS Mixin 列表：`src/main/resources/rs_integration.mixins.json`
- 共鸣磁盘 RS 契约：`src/main/java/com/huanghuang/rsintegration/resonance/disk/ResonanceDiskWrapper.java`
- 次元磁铁绑定：`src/main/java/com/huanghuang/rsintegration/mods/sophisticatedbackpacks/RSMagnetUpgradeItem.java`
- 次元磁铁执行：`src/main/java/com/huanghuang/rsintegration/mixin/sophisticatedbackpacks/MagnetUpgradeWrapperMixin.java`
- 精妙背包 RS 存取工具：`src/main/java/com/huanghuang/rsintegration/util/BackpackRSUtils.java`
- 一键吃 RS Grid/网络耦合：`src/main/java/com/huanghuang/rsintegration/autoeat/AutoEatEngine.java`
- 机器管理中心与侧栏：`src/main/java/com/huanghuang/rsintegration/sidepanel/`
- 远程机器标签投料和补燃料：`src/main/java/com/huanghuang/rsintegration/sidepanel/network/OpenBoundMachineGuiPacket.java`
- Apotheosis 图书馆：`src/main/java/com/huanghuang/rsintegration/mods/apotheosis/ApotheosisLibraryService.java`
- Apotheosis 刷怪笼升级：`src/main/java/com/huanghuang/rsintegration/mods/apotheosis/ApothSpawnerUpgradeService.java`
- FTB Quests 网络提交：`src/main/java/com/huanghuang/rsintegration/compat/ftbquests/`
- 村民/附魔/重铸/铁砧补货：`src/main/java/com/huanghuang/rsintegration/villager/`、`enchanting/`、`reforging/`、`anvilmemory/`
- 用户可见 RS 文案：`src/main/resources/assets/rs_integration/lang/zh_cn.json`

### BeyondDimensions

- 网络、成员和持久化：`src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/DimensionsNet.java`
- 网络统一存储：`src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/UnifiedStorage.java`
- 通用存储操作契约：`src/main/java/com/wintercogs/beyonddimensions/api/storage/handler/IStackHandler.java`
- 物品/NBT 身份：`src/main/java/com/wintercogs/beyonddimensions/api/storage/key/impl/ItemStackKey.java`
- 数量和资源返回值：`src/main/java/com/wintercogs/beyonddimensions/api/storage/key/KeyAmount.java`

## 附录 B：决策记录

| 决策 | 选择 | 原因 |
|---|---|---|
| 存储实现 | 复用 BD | API 完整，避免重复持久化和 UI 系统 |
| RSI 核心类型 | 后端无关 | 降低 RS/BD 版本耦合，支持测试和未来扩展 |
| 一次操作的网络来源 | 显式单后端 | 防止 RS+BD 同装时误取物品 |
| BD 绑定标识 | network ID | 网络不是以控制器坐标为身份 |
| 权限 | 适配器逐次校验 | BD 存储对象本身不携带调用玩家 |
| 异步规划 | 只使用不可变快照 | 避免跨线程访问 SavedData 和活网络 |
| 共鸣磁盘 | 首期 RS 专属 | 与 RS 磁盘 API 深度绑定，独立重构成本高 |
| 次元磁铁与网络升级 | 首期双后端必达 | 属于 RSI 自有功能，存取目标应由 `StorageReference` 决定 |
| 发布形态 | 优先单 JAR 双可选后端 | 用户体验最好，必要时再拆兼容 JAR |

### 17.17 2026-08-22：RS 业务 endpoint 迁移进度修订

本节覆盖 17.16 之后的实际工作树状态，用于覆盖此前“业务仍全部走旧 RS 路径”的过时判断；前文历史记录保留不改。

#### 已完成

- 核心合成、递归合成、异步节点、并行机器组、FTB 提交和账本退款已使用统一 storage endpoint 作为存取边界。
- Pure Daisy、Ars Imbuement 等并行机器取消时的 in-flight 退款状态已修复，并增加失败清理日志。
- 机器 delegate 的材料失败回滚、燃料补充、容器回收、产物回存已批量迁移，覆盖 Vanilla、Botania、Ars、Aether、Farmer's Delight、Youkai Homecoming、Forbidden、Embers、Goety、Malum、Avaritia、Wizards Reborn、Touhou Little Maid、Distant Worlds 等路径。
- 自动吃、次元磁铁、拾取、补货、喂食、机器管理中心和侧边栏的主要物品存取路径已切换到 endpoint；RS 绑定解析仍由 RS adapter 提供。
- 附魔台补货、FA/Malum 独立 CraftPacket、Lithum 燃料辅助、背包补货和离线退款回存已完成 endpoint 化。
- endpoint 已支持 `ServerPlayer`、普通 `Player`、假玩家和无玩家上下文；RS tracker 通知、材料缓存失效和容器同步已集中到 RS adapter 边界。
- 当前业务层全局扫描中，直接 `INetwork.extractItem/insertItem` 已基本只剩 RS driver、legacy endpoint、RS 网络解析辅助和 RS 原生 Crafting Grid Mixin。

#### 当前仍未完成

- `mods.toml` 仍将 `refinedstorage` 声明为强制依赖；无 RS 环境仍不能启动。
- 尚未实现 `BeyondDimensionsBackend`、BD session 注册、BD 网络解析和 BD 权限适配。
- RS 专属网络发现、绑定 NBT、Grid Mixin、共鸣磁盘和部分 RS UI 仍未完成可选类加载隔离。
- 当前 endpoint 的生产实现仍只有 RS；因此现阶段只能称为“RS 业务 endpoint 迁移完成度较高”，不能称为“RS/BD 二选一已支持”。

#### 阶段修订

当前不再是阶段 0，也不再是“只写抽象未迁移业务”的阶段。更准确的定位是：

1. 阶段 1 通用存储契约：已冻结并在 RS 业务路径中广泛使用。
2. 阶段 2 RS adapter 与 RS 业务迁移：主体已完成，仍需保留 RS-only 游戏回归。
3. 阶段 3 BD adapter：尚未开始正式接入，下一步应实现 BD `StorageSession`/`StorageBackend`、网络引用解析、成员权限和运行时注册。
4. 阶段 4 可选依赖与无 RS 类加载隔离：尚未完成，必须在 BD adapter 可用后再修改 `mods.toml`，并进行无 RS 客户端/专服启动验收。

#### 当前结论

RSI 现在已经具备较完整的后端无关业务边界，继续接入 BD 的改动重点从“逐个机器迁移”转为“实现 BD backend 和隔离 RS 专属类”。但在 BD backend、可选依赖和无 RS 启动验收完成前，发布物仍然是 RS 必需版本，不能对外宣称支持无 RS 独立运行。

### 17.18 2026-08-22：无 RS 启动隔离实施状态

- `mods.toml` 中 `refinedstorage` 已改为可选依赖。
- 主类已移除 RS API 字段/方法签名；共振磁盘、共振背包、RS 绑定、侧栏和 RS 网络监听集中到 `RSOptionalBootstrap`，仅在检测到 RS 时加载。
- 无 RS 时 common setup 会跳过 RS 业务模块注册；RS 专属 Mixin 由插件统一拒绝，避免目标类和 Mixin 本体提前加载。
- `compileJava` 与 `jar` 已通过，产物为 `build/libs/rs_integration-1.3.5.jar`。
- 仍需使用不含 RS 的 Forge 1.20.1 实例实际启动一次，确认 Forge/Mixin/可选模组组合的运行时边界；在该验证完成前，不宣称无 RS 已最终验收。

### 17.19 2026-08-23：BD 图执行失败根因与修复

日志中的 `delegate rejected graph dispatch after start attempt` 不是 BD 网络发现失败。失败链路已经确认是：BD 网络快照和计划生成成功，图节点账本也成功提交，但机器 delegate 只收到共享账本，没有收到该账本绑定的 `CraftStorageEndpoint`，随后 Enchantal Cooler 又调用旧的 `resolveNetworkForCraft`，在无 RS 的 BD 模式下得到空网络并拒绝启动。

本次修复包括：

- `ExtractionLedger` 暴露已选 endpoint；`AbstractBatchDelegate.useSharedLedger` 自动继承它。
- 图执行和普通异步执行在 delegate 准备阶段提前注入 endpoint，保证准备、材料预留、机器启动、燃料提取和退款使用同一个后端会话。
- Enchantal Cooler 的材料预留、燃料补充、机器清理和退款改为 endpoint 路径，不再以 RS `INetwork` 作为启动门禁。
- 图节点拒绝日志增加 delegate 类名和配方 ID，后续不会再只显示无上下文的通用失败字符串。

`compileJava` 已通过。该修复尚未替代完整 BD 游戏回归；下一次测试应使用 BD 网络中存放材料和燃料的 Enchantal Cooler 配方，确认材料能取出、机器实际开始工作、产物及失败退款回到同一 BD 网络。

### 17.20 2026-08-23：首轮 BD 回归日志的两个问题

植物科技实例的最新日志暴露了两个独立问题：

1. 多步橡木楼梯链在第一步完成后复用主 `ExtractionLedger`，但 `StorageSettlementLedger` 仍停留在 `COMMITTED`，第二步再次 `beginCommit()` 时抛出 `ledger state COMMITTED is not one of [OPEN]`。`ExtractionLedger.reset()` 现在同步清理 settlement mirror，允许同一链安全提交下一步。
2. `immortalers_delight:cooking/pitcher_sausage` 实际由 Farmer's Delight `CookingPotBatchDelegate` 执行，而非 Enchantal Cooler delegate。该 delegate 仍用 RS 网络解析作为启动门禁。现已改用选定 endpoint 预留材料、清空旧锅内容和执行启动检查，BD 网络不再被误判为不可用。

本轮 `compileJava` 已通过；需用新 JAR 回归这两个场景：多步普通合成应完成全部步骤，FD Cooking Pot 配方应从 BD 网络取料并实际进入烹饪状态。

### 17.21 2026-08-23：delegate endpoint 共性审计与 RS 回归保护

针对日志中出现的 `delegate rejected graph dispatch after start attempt`，本轮没有继续只修单个 delegate，而是对所有共享图入口和高风险私有账本入口做了共性审计。确认的风险模式有三类：

- 共享图已经选定 BD endpoint，delegate 启动时又重新解析 RS 网络并覆盖上下文；
- `validateAndInit` 或私有账本路径把 `network != null` 当作唯一存储门禁；
- 机器专属的 staff、ritual starter、燃料等附加物品仍直接读取 RS cache。

已完成的修复包括：

- Lithum altar 的 staff、燃料和燃料退款增加 endpoint 快照/提取/回存路径；
- Goety、Forbidden Arcanus、Touhou Little Maid、Wizards Reborn、Malum 的共享启动不再覆盖链 endpoint；FA 的 RitualStarterItem 支持 BD endpoint 提取、消耗耐久后回存和失败返还；
- Iron Furnaces 的网络解析和燃料候选改为 endpoint 优先，RS 仍保留原生 cache fallback；
- Aether、Avaritia、Crab Trap、Farmer's Delight、Farmer's Respite、Youkai Homecoming 等私有账本入口在 endpoint 存在时不再因 `INetwork` 为空而提前失败，并把 endpoint 注入本地账本；
- Botania 的若干世界交互 delegate 校验允许已选 endpoint，RS 模式仍按原 `INetwork` 路径执行；Clibano 的灵魂/燃料候选和失败回收也改为 endpoint 优先。

本轮再次执行 `compileJava --no-daemon`，构建成功；现有 Mixin、JEI 和 Forge deprecated warning 未新增为错误。RS 保护原则保持不变：RS 安装时仍由 `LegacyRsCraftStorageEndpoint` 使用原 `INetwork`，RS 网络的 `canRun`、权限、快照、模拟/正式转移和失败回收继续由 RS adapter 负责；本轮没有用 BD 对象伪装 `INetwork`，也没有删除 RS fallback。`git diff --check` 通过。

仍需真实游戏回归：RS 网络下递归合成、燃料补充、特殊 starter/staff、失败退款，以及 BD 网络下对应的 endpoint 路径。只有两套网络分别通过这些场景后，才能确认“RS 网络可用且 BD 不串网”。

### 17.22 2026-08-24：RS/BD 双后端选择隔离与 RS 回归修复

植物科技实例日志确认：BD 已能发现网络时，RS 失败并不是 RS 网络消失，而是关闭 RS 容器触发了全局 `onContainerClose`，无条件清空 RS 玩家网络解析缓存。下一次配方树请求没有终端上下文时，解析链因此错误落到 BD 或直接报“未找到可用的存储网络”。

本轮修复：

- 侧边栏关闭和普通容器关闭不再清理最后一次已验证的 RS 网络；只有 RS storage cache 真正失效、绑定变化、换维度、玩家退出或服务器清理才会失效。
- 侧边栏 listener 移除后保留 `lastKnownNetworks`，递归合成可以继续使用刚关闭的 RS 终端网络；网络 cache 重建或失效时会删除该保留值并重新注册。
- `ExtractionLedger` 的旧 `INetwork` 重载现在会优先使用已注入的 `CraftStorageEndpoint`，避免 BD endpoint 被遗留委托误判为空，也避免 RS/BD 同时安装时被旧网络参数覆盖。
- Pure Daisy 和 Runic Altar 的独立提取、共享账本路径已补齐 endpoint 选择；RS 仍保留原生 fallback。

后端选择规则现明确为：显式 `StorageReference` 优先；无显式目标时先尝试玩家当前 RS 终端/网络物品/绑定和最近已验证 RS session，再按注册顺序选择 backend 默认 session（当前为 RS、随后 BD）；一旦选定，整条递归链、账本、delegate、退款和产物回存都不得重新选择另一个 backend。日志中的 `BD discovery` 仅表示 BD 可发现网络，不代表 RS 不可用。

本轮 `compileJava --no-daemon` 已通过。最终 JAR 需在包含 RS 和 BD 的实例中回归：关闭 RS 终端后继续递归合成、RS 单网络、BD 单网络、双网络显式选择，以及中间步骤失败退款。当前仍不能据此宣称所有委托已完成 BD 游戏验收；任何仍直接调用 `RSIntegrationNetwork` 的功能（RS 侧栏、RS 专属绑定/共鸣系统等）仍是 RS 专属功能，不应被 BD 默认选择覆盖。

### 17.23：RS 单网络递归合成解析断裂修复

植物科技实例最新日志显示：RS 后端启动注册成功，侧边栏也能同步粉色创造控制器的库存，但 JEI 递归合成请求只进入 BD discovery，随后报“未找到可用的存储网络”；已有 RS 网络下执行中间步骤又因没有 endpoint 快照而报告材料缺失。根因是侧边栏、RS backend 和递归合成分别维护网络上下文：侧边栏成功解析的 `INetwork` 没有发布到玩家通用解析缓存，且计划阶段的持久化 RS 引用在控制器暂时不可严格解析时会覆盖仍然有效的终端会话。

本轮修复：

- 新增统一的 `RSIntegrationNetwork.rememberResolvedNetwork`，侧边栏刷新和 listener 注册在成功认证 RS 网络后立即发布同一玩家会话；递归合成、RS backend 和关闭侧边栏后的请求共享这份缓存。
- listener 替换过程会使短期解析缓存失效，注册完成后重新发布网络，避免“侧边栏显示正常、下一请求解析为空”。
- 计划阶段对 RS 引用增加 live session 回退：引用坐标暂时不可用时，若当前玩家已有经过认证的 RS 会话，则继续使用该会话，不把有效 RS 网络误判为不可用。
- 未改变 BD 选择规则，也未让侧边栏成为存储核心依赖；RS、BD 仍通过各自 `StorageBackend`/`StorageSession` 隔离。

本轮 `compileJava --no-daemon` 已通过。新 JAR 需要回归 RS 单网络（侧边栏打开后关闭再从 JEI 下单）、RS+BD 双网络显式选择、BD 单网络，以及中间步骤材料从网络提取和失败退款。只有这些场景通过后，才能确认本次 RS 回归和 BD 兼容没有互相覆盖。

### 17.24：RS 执行阶段旧引用回退

01:11 的回归日志进一步确认：RS NetworkItem provider 已成功解析网络，侧边栏也同步了 RS 库存，计划响应成功；但执行阶段仍可能因计划携带的旧 `StorageReference` 严格坐标解析失败而直接报网络不可用，或使账本 endpoint 快照为空。此前只在计划阶段做了 RS live-session 回退，执行阶段仍存在断点。

本轮修复：

- 递归执行阶段与计划阶段统一处理 `StorageReference`：RS 引用失效时优先复用当前已认证的 RS `INetwork`，再尝试当前 RS 默认会话；不会把显式 BD 引用改成 RS。
- 无显式存储引用且旧网络参数为空时，执行阶段调用统一 `CraftStorageEndpoints.resolveDefault`，确保 RS 单网络、BD 单网络和双后端选择都经过同一后端注册表。
- 增加 `[RSI-ExecAvail]` INFO 诊断，明确记录 recipe、legacy network、endpoint backend、storage reference 和输出目标，后续可直接判断失败发生在网络解析还是材料快照。
- RS driver 的可用性校验不再要求二次坐标查找返回同一个 `INetwork` 实例；已认证句柄只需保持运行、存在世界和 storage cache，即可继续使用。显式引用解析仍执行坐标、区块加载和网络归属校验。

`compileJava --no-daemon` 已通过。需要使用新 JAR 复测 RS 单网络：侧边栏同步后关闭，从 JEI 下单橡木楼梯并确认 `[RSI-ExecAvail] endpoint=refinedstorage`；再验证 RS+BD 显式选择和 BD 单网络，确保没有跨后端回退。

### 17.25：RS 坐标不再作为长期授权

本轮审计发现，旧的 `StorageReference(refinedstorage, v1|...)` 即使玩家已经丢弃无线终端，只要控制器坐标仍加载，仍可能被坐标解析器重新打开。这会让玩家已经切换到 BD 后，递归合成继续写入 RS。

修复后的规则：

- RS 默认后端只接受当前存在的 RS 凭据：玩家背包/Curios 中的 NetworkItem、当前 RS 容器、活动的侧边栏监听器，或明确绑定到机器的 RS 网络；解析缓存和侧边栏的 last-known 网络不算凭据。
- 计划携带的 RS 坐标必须与当前凭据解析出的同一个网络匹配；仅凭坐标不能授权。
- 无当前 RS 凭据时，递归合成会继续走后端注册表，允许 BD 成为默认存储；RS 坐标只保留为历史计划信息。
- 为避免附近控制器或旧缓存再次抢占 BD，递归合成的通用 RS 解析不再自动采用附近 RS 节点。
- 侧边栏重新刷新也必须重新取得当前 RS 凭据；关闭面板后保留的 last-known 网络不会被用来重新认证。

因此，RS 与 BD 同时安装时，选择结果由当前可用凭据和计划中的明确 BD 引用决定；丢弃 RS 凭据后不会因旧坐标残留继续使用 RS。

### 17.26：范围绑定后端无关化

范围绑定原先在 `NearbyBindingService` 内直接调用 RS `NetworkItem` 和 `RSBindingHook`，即使 BD 终端已经支持普通机器绑定，范围扫描仍只能绑定 RS。

本轮调整：

- 范围绑定现在通过 `AltarBindingRegistry.findHook` 选择当前手持/装备的存储终端，并调用对应 `IBindingHook.createBinding`；RS、BD 以及后续存储后端共用同一套扫描、权限、去重和持久化流程。
- 移除了范围绑定服务对 RS 原生 `NetworkItem` 的直接引用，RS 缺失时不会因为该服务加载失败而影响 BD 单独启动。

### 17.27：双网络显式选择与规划快照一致性修复（2026-08-24）

植物科技实例日志进一步确认了一个选择状态漏洞：用户点击过 RS 后，异步纯规划或 typed 规划回调没有携带原始 `StorageReference`，回调重新按默认顺序解析，导致后续请求重新锁回 RS。执行阶段还存在“显式引用解析失败后复用 live RS session”的兜底，这会让旧 RS 引用越过当前选择继续生效。

本轮修复：

- 显式 `StorageReference` 现在是严格目标。计划或执行解析失败时直接返回网络不可用，不再复用旧 RS session、RS 默认网络或其他后端默认网络。
- 异步纯规划、同步回退、typed 预览队列和最大可合成数量探测全部传递同一个 `StorageReference`；计划和执行不会因为跨线程/跨阶段丢失后端选择。
- 执行缓存校验按选定引用重新获取同一后端快照；BD 计划不会再用 RS 当前网络计算 fingerprint，RS 计划也不会借用 BD 快照。
- 计划界面刷新以服务端引用和网络列表为准，服务端返回空引用时清除旧选择，避免 UI 残留网络。

日志中的 `storedItems`/`availableKeys` 不代表同一层级：前者是后端快照条目数，后者是叠加玩家背包后的去重键数；侧边栏 `panel entries` 还是独立的 UI 索引条目数，不能直接互相比较。真正的快照一致性以 `StorageReference` 后端身份和材料 fingerprint 同时匹配为准。

`compileJava --no-daemon` 已通过。新 JAR 需要回归：RS 单网络、BD 单网络、RS+BD 双网络先选 BD 再选 RS、切换后连续下单，以及异步多步合成。验证日志中不应再出现 `execution reused live RS session`；每次 `[RSI-ExecAvail]` 的 `endpoint` 必须与界面最后选择的 backend 一致。

### 17.28：RS 旧引用兼容与 BD 计划库存快照修复（2026-08-24）

本轮确认的两个现象相互独立：RS 无法使用来自旧版过渡 endpoint 生成了不可解析的 `dimension@BlockPos{...}` 引用；BD 存档中的递归树数量不更新来自预览缓存复用了库存已变化的完整 `PlanResponse`。

- `LegacyRsCraftStorageEndpoint` 现在统一生成 `v1|dimension@x,y,z`，与 typed RS backend 使用同一引用格式。
- 收到旧引用或暂时失效的 RS 引用时，只允许用玩家当前已认证的 RS 终端/容器/绑定网络刷新；不会使用侧栏 retained session，也不会跨到 BD。刷新后的 canonical reference 会回写规划快照和异步请求。
- 递归树计划缓存命中改为完整 `PlanningStateValidator.sameState`，库存种类或数量发生变化都会重新读取后端快照并生成计划，不再把旧 `materials.available()` 返回给 BD 界面。

本轮完成 `compileJava`、`jar` 和 `git diff --check` 后，仍需用最新 JAR 分别验证 RS-only、BD-only，以及 RS+BD 下切换后连续下单；BD 重点确认合成一次后再次打开递归树时可用数量即时减少。

### 17.29：产物去向隐藏内部引用（2026-08-24）

RS 默认发现描述曾直接把内部 `v1|维度@x,y,z` 引用作为网络显示名，导致配方树“产物去向”显示协议字符串。现在客户端显示层会将 RS 规范引用渲染为本地化的“RS 网络”，BD 数字 network ID 渲染为“BD 网络 #ID”；真实 `StorageReference` 仍原样保留在网络包、计划快照和执行链中，不影响后端选择与权限校验。

### 17.30：RS 持有时 BD 候选可见性审计（2026-08-24）

植物科技实例日志中 `BD discovery networks=1 refs=[0]` 已证明持有 RS 终端时 BD 网络仍能被发现；同一请求的 `endpoint=refinedstorage` 只表示当前计划默认选中了 RS，并不表示 BD 被屏蔽。计划生成现在额外记录合并后的候选列表，例如 `choices=[refinedstorage@..., beyonddimensions@0]`，用于确认网络选择包是否完整到达客户端。
- `rsi.binding.nearby.no_connector`、`multiple_connectors`、`invalid_connector` 重命名为后端无关的 `no_terminal`、`multiple_terminals`、`invalid_terminal`，中英文文案同步改为“存储网络终端”。
