# BeyondDimensions 独立存储后端可行性评估报告

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

直接取得 `UnifiedStorage` 后进行插入、提取，不会自动携带发起玩家上下文。因此 **BD 适配器必须在访问存储前自行验证成员关系**，不能因为知道网络 ID 就允许操作。

首期建议权限映射：

| RSI 操作 | RS | BD |
|---|---|---|
| 查看/规划 | RS view/extract 相关策略 | 必须是网络成员 |
| 提取材料 | `Permission.EXTRACT` | 必须是网络成员 |
| 插入/回收 | `Permission.INSERT` | 必须是网络成员 |
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
- BD：潜行右键 BD 网络方块/终端，或在玩家只有一个主网络时通过绑定按键确认，生成 BD network ID 引用。
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
