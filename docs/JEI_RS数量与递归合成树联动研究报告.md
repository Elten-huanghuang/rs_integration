# JEI RS/BD 数量显示与递归合成树联动研究报告

## 当前实现进度

已完成第一、第二阶段实现：

- JEI 物品格右下角显示当前 RS/BD 网络库存；库存默认使用 0.70 倍缩放，左上角红色计划缺口默认使用 0.75 倍缩放，长数字自动缩小到物品格宽度以内。两个字号可在客户端配置中分别调整。
- JEI 15.20（`mezz.jei.gui.overlay.*`）与 15.49（`mezz.jei.gui.overlay.ingredients.*`）分别使用版本专用薄 Mixin，运行时只应用实际存在的目标。
- 通过 `enableJeiNetworkOverlay` 配置项控制，并随服务端配置同步到客户端。
- 首次连接或切换网络时发送完整快照，之后只发送数量发生变化的条目；归零条目以零数量增量删除。
- RS 与 BD 共用现有 `StorageSession`/`StorageRestockSupport` 选择规则，不合并两个网络的库存。
- 玩家退出、失去查看权限、网络不可用或功能关闭时清空客户端缓存。
- RS 使用 `IStorageCacheListener`，BD 0.7.30 使用 `UnifiedStorage.subscribeDelta`；事件只标记发生变化的物品，并回查该物品的权威绝对数量。
- 每 20 tick 只校验当前网络引用、查看权限和订阅句柄是否仍有效，不再周期性遍历完整库存。仅不支持订阅的未知后端使用快照求差回退。
- 全量快照支持分块，并携带 `epoch + sequence + chunkIndex/chunkCount`；客户端发现乱序或缺号时限流请求重同步。
- 最近一次递归合成计划会建立独立的 JEI 需求上下文：右下角白色数字仍为真实库存，左上角红色 `-数量` 为计划净缺口。
- 缺口覆盖层由独立的 `enableJeiCraftingShortageOverlay` 配置项控制，并且只有计划与当前 RS/BD 网络引用一致时才显示，避免跨网络误报。
- 红色缺口以计划生成时的库存和净缺口为基准，结合后续增量库存缓存实时派生；入库会减少缺口，提取会增加缺口，补齐后红字消失，不触发重新规划或额外网络请求。
- 合成计划的缺失诊断在客户端保留可解析的物品注册 ID；即使某个兼容配方只提供诊断、没有写入结构化材料表，只要能唯一解析为物品，单项和“全部加入收藏”入口都会显示。

当前实现的重发条件被限制为：首次绑定、切换网络、原生缓存/订阅失效、权限恢复，以及客户端检测到序列中断后主动请求。正常库存增减只发送合并后的变化物品，不发送完整网络清单。

显示开关位于 `config/rs_integration/common.toml`，两层可以分别关闭：

```toml
[integrations]
enableJeiNetworkOverlay = true
enableJeiCraftingShortageOverlay = true
```

字号属于本地视觉偏好，位于 `config/rs_integration/client.toml`，不会参与服务端配置同步：

```toml
[jeiOverlay]
networkCountScale = 0.7
craftingShortageScale = 0.75
```

## 1. 报告目的

本报告研究两项功能的合并方案：

1. 将 ExtendedAE Plus 的“JEI 物品上显示网络库存数量/可合成标记”移植到 `rs-integration`，同时兼容 Refined Storage（RS）和 Beyond Dimensions（BD）。
2. 将该显示与本项目已有的递归合成树“需要多少、已有多少、还缺多少”模型联动。

结论先行：功能可行，但不应直接复制 ExtendedAE 的 AE2/无线终端实现。`rs-integration` 已经有双后端存储抽象、递归规划器、合成树模型和 JEI Mixin，应采用“当前后端权威库存快照 + 服务端递归缺口计算 + 客户端 JEI 覆盖层”的架构。RS 和 BD 应被视为两个可选的、彼此独立的存储会话，默认不把两个网络的库存相加。

## 2. 现有代码基础

### 2.1 ExtendedAE Plus 可复用的思想

ExtendedAE Plus 的相关实现位于：

- `ExtendedAE_Plus-develop-1.20.1/src/main/java/com/extendedae_plus/server/JeiSyncManager.java`
- `ExtendedAE_Plus-develop-1.20.1/src/main/java/com/extendedae_plus/client/jei/NetworkItemCache.java`
- `ExtendedAE_Plus-develop-1.20.1/src/main/java/com/extendedae_plus/mixin/jei/IngredientListRendererMixin.java`
- `ExtendedAE_Plus-develop-1.20.1/src/main/java/com/extendedae_plus/network/jei/SyncNetworkInventoryS2CPacket.java`

可以借鉴的部分：

- 首次发送完整快照，之后只发送变化项。
- 客户端按物品身份缓存数量和可合成状态。
- JEI 渲染层只负责查询缓存和绘制，不执行服务端逻辑。
- 网络断开时主动清空客户端缓存。

不应直接移植的部分：

- AE2 `AEKey`、`IGrid`、`MEStorage` 访问。
- ExtendedAE 的无线终端定位方式。
- 每秒对整个 AE 网络的完整可合成集合进行计算。

### 2.2 本项目已有的双后端存储抽象

关键代码：

- `storage/StorageSession.java`
- `storage/StorageSnapshot.java`
- `storage/StorageSnapshotResult.java`
- `storage/rs/RefinedStorageSnapshotMapper.java`

推荐的权威库存来源是：

```java
StorageSession session;
StorageSnapshotResult result = session.snapshotItems(player);
```

`StorageSnapshot` 已经处理了：

- RS 物品身份和 NBT 区分。
- 同一物品键的数量合并。
- `long` 数量。
- 后端不可用和无效响应的区分。
- `StorageItemKey` 作为统一身份。

因此 JEI 数量层不应重新访问 RS 原生内部缓存，而应使用该抽象。这样以后替换存储后端时，JEI 功能不需要重写。

当前项目已经同时提供两个后端：

| 后端 | 后端 ID | 网络标识 | 会话实现 | 物品快照来源 |
|---|---|---|---|---|
| Refined Storage | `refinedstorage` | RS 原生网络引用 | `RefinedStorageSession` | `RefinedStorageDriver.snapshotItems()` |
| Beyond Dimensions | `beyonddimensions` | BD 网络数字 ID 的字符串形式 | `BeyondDimensionsSession` | BD `UnifiedStorage.getStorage()` 中的物品键条目 |

BD 适配器已经将原生 BD 类隔离在 `storage/bd` 包中，并通过反射边界访问可选 API。移植功能应继续保持这一边界：JEI、递归规划器和通用网络包只能依赖 `StorageSession`、`StorageSnapshot`、`StorageItemKey`，不能直接导入 BD 原生类。

### 2.3 RS/BD 当前会话的选择规则

`StorageRestockSupport` 已经体现了一个重要原则：如果玩家手持、背持或通过 Curios 装备了 BD 网络终端，应优先使用该 BD 网络，避免 RS-first 的默认顺序抢走用户明确选择的后端。

数量同步也应使用同一规则：

```text
显式 BD 终端/BD 终端界面 -> BD 指定网络
否则 -> StorageBackendRegistry 的默认解析结果
```

RS 和 BD 同时安装时，不应使用“两个后端的数量合计”作为默认显示值。原因是递归合成提交、权限、提取和计划执行都绑定在一个 `StorageReference` 上；把两个网络合并显示会产生“看起来够用、实际无法从当前网络提取”的错误。

只有在未来明确实现“多网络联合计划”后，才可以引入：

```text
List<StorageReference> activeNetworks
```

在当前版本中，建议保持一个玩家一次只有一个 active storage session。

### 2.4 本项目已有的递归合成树数据

关键代码：

- `crafting/plan/PlanResponse.java`
- `crafting/tree/PlanTreeNode.java`
- `crafting/tree/PlanTreeModel.java`
- `crafting/planning/PureRecipePlanner.java`
- `crafting/planning/PureDemandTreeInspector.java`
- `crafting/plan/PlanRenderEngine.java`
- `crafting/plan/CraftingPlanScreen.java`

当前合成树已经保存三种不同含义的数量：

| 字段 | 含义 |
|---|---|
| `needed` | 该节点的总需求量/毛需求 |
| `available` | 当前计划快照中可用的库存量 |
| `missingCount` | 计划无法由当前库存覆盖的缺口 |
| `unresolved` | 图规划阶段没有解析到的输入数量 |
| `edgeQuantity` | 父节点生产边实际携带的数量 |

`PlanResponse.Availability` 已明确区分“毛需求”和“净缺口”：

```java
needed       // 计划总共需要
available    // 当前实际已有
missingCount // max(0, needed - available)，或服务端计算出的净外部缺口
```

这一区分非常重要。JEI 中显示的 RS 库存数量只能来自实时库存快照，不能直接用 `needed - available` 代替库存数量。

### 2.5 接口调研结论（RS、BD、JEI）

#### RS：可以事件驱动增量

项目已经在 `VoidUpgradeCoordinator` 和 `RSSidePanelNetworkHandler` 中使用：

```java
com.refinedmods.refinedstorage.api.storage.cache.IStorageCache<ItemStack>
com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener<ItemStack>
```

可用回调：

```java
onAttached()
onInvalidated()
onChanged(StackListResult<ItemStack>)
onChangedBulk(List<StackListResult<ItemStack>>)
```

调研到一个重要语义：RS 的 `onChanged` 回调中的 `StackListResult.getStack()` 可能是变化前的栈，不能直接用 `stack.getCount()` 作为变化后的绝对数量。现有 `RSSidePanelNetworkHandler` 的正确做法是：

1. 监听回调只记录受影响的 stack/id。
2. 在当前 tick 结束时合并同一物品的重复变化。
3. 再从 `IStorageCache.getList()` 查询真实剩余数量。
4. 数量为 0 时发送删除或零数量更新。

JEI 库存同步应直接复用这个模式，而不是自己依赖 `change` 字段累加。原因是多个外部存储器可能在一个 tick 内同时改变同一物品，累加容易受到顺序、丢失或重建影响。

`onInvalidated()` 表示 RS 缓存对象失效，不代表库存为空。此时应解绑旧 listener、获取新 cache、发送一次新的完整快照；不能把 invalidated 直接当成 REMOVE 全部物品。

#### BD 0.7.30：也可以事件驱动增量

对 `libs/beyonddimensions-1.20.1-forge-0.7.30.jar` 进行接口检查后确认：

```text
com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage
    extends UnorderedStackHandlerRemoveZero
    extends AbstractUnorderedStackHandler
```

`AbstractUnorderedStackHandler` 提供：

```java
AutoCloseable subscribeDelta(Object owner, DeltaListener listener)
AutoCloseable subscribeAny(Object owner, AnyChangeListener listener)
```

其中：

```java
interface DeltaListener {
    void onDelta(IStackKey<?> key, long amount, boolean inserted);
}
```

这比预期更好：BD 的 `UnifiedStorage` 可以监听每个物品键的增减，不必周期性扫描整个 `getStorage()` 列表。当前工程为避免在未安装 BD 时链接原生类，BD 适配器采用反射隔离；因此实现时应在 `BeyondDimensionsReflection` 内增加订阅/取消订阅包装，向通用层暴露自己的 `StorageDeltaListener`，不要让 BD 原生 listener 类型泄漏到 common、JEI 或递归规划代码。

BD 订阅必须保存返回的 `AutoCloseable`，在以下情况调用 `close()`：

- 玩家切换 BD 网络 ID。
- BD 网络被销毁。
- 玩家退出或服务端停止。
- 重新绑定到新的 `UnifiedStorage` 实例。

BD 的 `subscribeDelta` 是库存变化通知，不包含完整权限语义；每次建立会话仍需通过现有 `StorageSession.checkPermission` 验证玩家权限。事件回调中的 key 和数量只用于标记 dirty/构建增量，必要时再通过 `getStackByKey` 或 `getStorage` 获取权威数量。

#### JEI 15.20.0.129：当前渲染入口可用

本项目 `libs` 中的 JEI 15.20.0.129 仍提供：

```java
mezz.jei.gui.overlay.IngredientListRenderer.render(GuiGraphics)
mezz.jei.gui.overlay.IngredientListRenderer.getSlots()
mezz.jei.gui.overlay.IngredientListSlot.getOptionalElement()
mezz.jei.gui.overlay.IngredientListSlot.getArea()
mezz.jei.gui.overlay.IngredientListSlot.getPadding()
```

因此可以在 `IngredientListRenderer.render` 尾部增加独立覆盖层 Mixin。项目已有的 `IngredientListRendererGridMixin` 只负责批量渲染上下文标记，数量覆盖层应保持独立，避免修改同一个 `renderBatch` 调用造成注入耦合。

## 3. 目标数据模型

建议新增一个跨 JEI、RS Grid、合成树都能使用的状态模型：

```java
public record ItemNetworkState(
        StorageItemKey key,
        long storedAmount,
        long grossDemand,
        long plannedAvailable,
        long missingAmount,
        boolean craftable,
        AvailabilityState state,
        long inventoryRevision,
        long planRevision
) {}
```

状态枚举建议：

```java
public enum AvailabilityState {
    UNKNOWN,
    STORED,
    PARTIAL,
    CRAFTABLE,
    STORED_AND_CRAFTABLE,
    MISSING,
    UNRESOLVED,
    TIMEOUT
}
```

字段语义：

- `storedAmount`：当前 RS 网络的真实数量，只来自 `StorageSnapshot`。
- `grossDemand`：当前合成树对该物品的总需求，对应 `needed`。
- `plannedAvailable`：生成当前合成计划时使用的库存，对应 `available`。
- `missingAmount`：当前计划外部仍缺少的数量，对应 `missingCount` 或递归规划器输出的净缺口。
- `craftable`：是否存在服务端认可的可执行递归路径。
- `inventoryRevision`：库存快照版本。
- `planRevision`：合成计划版本或请求版本。

不要把所有字段混成一个数字。至少要同时保留：

```text
真实库存 storedAmount
计划需求 grossDemand
计划缺口 missingAmount
```

## 4. “终端数量”和“合成树缺口”的结合方式

### 4.1 两种数量必须分开

假设合成树需要 64 个铁锭，RS 当前有 20 个：

```text
JEI 数量显示：20
合成树显示：20 / 64
合成树缺口：44
```

不能在 JEI 中显示 `44`，因为 44 是缺口，不是库存；也不能把 JEI 显示改成 `20/64`，因为 JEI 物品列表通常没有唯一的当前计划上下文。

推荐显示（数量位置按 ExtendedAE Plus，位于物品格右下角）：

| 场景 | JEI 主数字 | 附加标记 | 颜色/提示 |
|---|---:|---|---|
| 有库存，无当前计划 | `20` | 无 | 普通库存色 |
| 有库存且可递归合成 | `20` | `+` | 库存色 + 可合成标记 |
| 无库存但可递归合成 | `0` 或 `Craft` | `+` | 可合成色 |
| 当前合成树缺 44 个 | `20` | `-44` 或缺口标记 | 缺口色，仅在树上下文可用时 |
| 不可合成且库存不足 | `0` | 无 | 红色/不可用 |

### 4.2 合成树打开时建立“计划上下文”

合成树是唯一知道 `needed` 和 `missingAmount` 的地方。建议在客户端维护一个可选的当前计划上下文：

```java
public final class ActiveCraftPlanContext {
    private static PlanResponse plan;
    private static long revision;

    public static void accept(PlanResponse response) { ... }
    public static void clear() { ... }
    public static Optional<PlanMaterialState> find(StorageItemKey key) { ... }
}
```

上下文保存最近一次 `PlanResponse`，不再和 `CraftingPlanScreen` 的可见状态绑定：

- 收到新计划时覆盖旧上下文。
- 当前 RS/BD 网络引用必须与计划一致。
- 切换到不同网络、切换世界或退出服务器时清除。

因此关闭合成树后，通过普通入口打开 JEI 仍可查看最近一次计划缺口；同时不会把一个网络的计划带到另一个网络。

### 4.3 合成树状态转换

收到 `PlanResponse` 后，将 `plan.materials()` 转为按 `IngredientKey` 索引的快照：

```text
IngredientKey
    -> needed
    -> available
    -> missingCount
```

对 JEI 的 `ItemStack` 查询时，使用与 `PlanResponse.availability(stack)` 相同的精确键和无 NBT 回退规则。不要只按 `Item` 注册表 ID 查询，否则会把不同 NBT 变体混在一起。

当 JEI 物品与当前计划中的材料匹配时，可在 tooltip 或小型附加标记中显示：

```text
库存：20
当前计划需求：64
当前计划缺少：44
```

当 JEI 物品不是当前计划材料时，只显示普通 RS 库存信息。

## 5. 推荐整体架构

```text
RS StorageSession
        |
        v
ServerInventorySnapshotManager
        |  周期快照/增量变化
        v
ClientItemNetworkCache <---------------- PlanResponse
        |                                      |
        |                                      v
        |                            ActiveCraftPlanContext
        |
        +--> JEI IngredientListRenderer 覆盖层
        |
        +--> RS Grid 递归标记覆盖层
        |
        +--> 合成树 tooltip/书签/缺口联动
```

递归规划单独走按需请求：

```text
JEI 当前可见物品
        |
        v
ClientCraftabilityRequestCache
        |
        v
Server RecursiveAvailabilityService
        |
        +--> CraftingResolver / PureRecipePlanner
        +--> 当前 RS StorageSnapshot
        +--> 当前机器绑定与配置
        |
        v
CraftabilityResponse
```

## 6. 服务端库存同步方案

建议新增：

```text
server/JeiInventorySyncManager.java
network/packet/JeiInventorySnapshotPacket.java
client/jei/JeiItemNetworkCache.java
```

### 6.1 同步内容

每个条目包含：

```text
serial
StorageItemKey（完整条目或增量包中省略）
storedAmount
inventoryRevision
```

服务端维护玩家级状态：

```java
Map<StorageItemKey, Long> previousAmounts;
Map<StorageItemKey, Long> serials;
long nextSerial;
long lastRevision;
```

### 6.2 首次全量、后续严格增量

同步策略必须是“首次一次全量，后续只发增量”，而不是周期性重复发送完整网络库存：

1. 玩家首次进入有效 RS/BD 网络时发送一次完整快照。
2. 后续只发送新增、数量变化、删除和可合成状态变化。
3. 玩家切换 `StorageReference`（RS 网络或 BD 网络 ID）时发送一次新全量，并递增 `sessionEpoch`。
4. 网络断开、权限失效或 BD 网络销毁时发送一次 clear。
5. RS 使用 `IStorageCacheListener`，BD 0.7.30 使用 `UnifiedStorage.subscribeDelta`；两者都在事件到达时标记变化并排队。只有在后端事件不可用、缓存失效或重连恢复时才做一次快照。

事件只用于发现变化，绝不能导致重复全量包；JEI 渲染和鼠标操作都不直接访问服务端。可以在 tick 末尾批量发送合并后的增量，避免一帧内的多次插入/提取产生多个包。

### 6.3 增量协议优化

保留 ExtendedAE 的 serial 思路，但增加会话和顺序校验：

```text
SyncHeader {
    sessionEpoch
    baseRevision
    targetRevision
    fullSnapshot
    sequence
}

Entry {
    serial
    keyPresent
    key                  // 仅 ADD/full snapshot 携带
    amount
    flags                // ADD / UPDATE / REMOVE
    craftable             // 可选；递归状态也可拆成独立包
}
```

服务端每个玩家、每个 `StorageReference` 维护：

```java
Map<StorageItemKey, Long> serials;
Map<StorageItemKey, Long> previousAmounts;
long nextSerial;
long revision;
long sequence;
long sessionEpoch;
```

后端监听到变化后的统一流程为：

```text
RS IStorageCacheListener.onChanged/onChangedBulk
BD UnifiedStorage.subscribeDelta
                |
                v
StorageDeltaQueue（按后端、networkId、StorageItemKey 合并）
                |
                v
tick 末尾读取权威绝对数量
                |
                v
生成 ADD / UPDATE / REMOVE 增量包
```

RS 读取绝对数量时使用 `IStorageCache.getList()`；BD 优先使用 `getStackByKey`，若该调用在某个版本不可用再退回 `getStorage()`。事件中的增量值不直接作为客户端最终数量。

客户端只接受 `epoch == currentEpoch` 且 `sequence == lastSequence + 1` 的包。发现序号跳跃、epoch 不匹配或 `baseRevision` 不一致时，客户端发送一次 `ResyncRequestPacket`；服务端只在该请求时重新发送全量快照。这样可以处理丢包/乱序，同时不会把全量同步变成常规流量。

增量处理规则：

- 新物品分配新 serial，发送 `ADD + key + amount`。
- 数量变化只发送 `UPDATE + serial + amount`，不重复发送 key。
- 数量变成 0 且不可合成时发送 `REMOVE + serial`。
- 数量为 0 但仍可递归合成时保留 serial，发送 `UPDATE(amount=0, craftable=true)`。
- 同一 tick 内同一 serial 的多次变化合并成最后状态。
- RS 与 BD、或不同 BD 网络 ID 之间不复用旧 epoch 的 serial。

### 6.4 分块和带宽控制

- 按条目数和估计字节数双重上限分块，避免大 NBT 导致单包过大。
- 空增量不发包。
- 高频产出可在 2～5 tick 窗口内合并，减少机器连续产出造成的包数量。
- 数量、serial、revision、sequence 使用 VarLong；完整 key 只在 ADD/full snapshot 发送。
- 小增量包不默认压缩；仅当完整快照超过阈值时考虑压缩。

### 6.5 库存同步与递归状态同步分离

库存数量和递归可合成状态的变化频率不同，建议拆成两个逻辑流：

```text
InventoryDeltaPacket
    storedAmount、serial、revision

RecursiveAvailabilityPacket
    key/serial、craftable、state、dependencyRevision、requestId
```

库存变化时不自动重新计算所有递归状态。递归状态只在 JEI 当前可见物品被请求、当前合成树依赖材料发生变化、对应缓存过期或用户主动刷新计划时重新计算。这样既保持 ExtendedAE 的增量库存同步，又避免递归规划器成为新的服务器压力源。

### 6.6 网络安全与健壮性

- 客户端只接收 S2C 快照，不允许客户端伪造库存。
- `decode()` 对条目数和字符串/NBT 大小设置硬上限。
- 客户端收到新世界/登出事件时清空缓存。
- 缓存条目必须带 `StorageReference` 或 session epoch，防止旧包覆盖新网络状态。

## 7. 递归可合成状态方案

### 7.1 不要每帧调用递归规划器

禁止以下路径：

```text
JEI 每帧绘制
 -> CraftingResolver
 -> PureRecipePlanner
```

递归规划只能在服务端执行，并通过短期缓存返回结果。

### 7.2 请求模型

新增按物品查询的请求/响应：

```text
RecursiveAvailabilityRequestPacket
    StorageItemKey key
    requestId
    clientPlanRevision（可选）

RecursiveAvailabilityResponsePacket
    StorageItemKey key
    requestId
    craftable
    state
    missingAmount（可选）
    dependencyRevision
```

服务端执行：

1. 解析 `StorageItemKey` 对应的物品。
2. 获取当前 RS `StorageSnapshot`。
3. 复用递归合成图和现有限制：`craftingMaxDepth`、`craftingMaxSteps`、超时、机器绑定等。
4. 返回 `CRAFTABLE`、`MISSING`、`UNRESOLVED` 或 `TIMEOUT`。

### 7.3 缓存与限流

建议初始参数：

| 项目 | 建议值 |
|---|---:|
| 可合成成功缓存 | 2～5 秒 |
| 缺材料缓存 | 1～2 秒 |
| 超时/未解析缓存 | 500ms～1 秒 |
| 单玩家请求上限 | 32～64 次/秒 |
| 客户端同时 pending | 64～128 条 |
| 单次 JEI 可见物品入队 | 16～32 个 |

可先复用现有 `RecipeAvailabilityCache` 的 ticket、过期和请求限流模式，但不要把它的 `RecipeAvailabilityKey` 直接当成物品库存键。前者是“某个机器配方的材料检查”，后者是“某个物品在当前 RS 网络中的状态”。

## 8. 与合成树缺口的具体联动策略

### 8.1 计划内优先显示缺口

当 `ActiveCraftPlanContext` 存在时，客户端查询结果按以下顺序合并：

```text
storedAmount       <- ClientItemNetworkCache
needed             <- ActiveCraftPlanContext
missingAmount      <- ActiveCraftPlanContext
craftable          <- RecursiveAvailabilityCache
```

显示逻辑：

```text
if 当前计划中存在该物品:
    显示真实库存 storedAmount
    tooltip 增加 needed / missingAmount
    若 craftable，增加递归可合成标记
else:
    只显示真实库存和普通 craftable 状态
```

### 8.2 计划缺口和实时库存不一致时

合成树的 `PlanResponse` 是某一时刻的服务端计划快照；JEI 数量是后续实时同步数据，二者可能短暂不一致。

建议在状态中显示数据新鲜度：

```text
实时库存 revision != 计划生成 revision
    -> 保留计划的 needed/missing
    -> 使用最新 storedAmount
    -> tooltip 标注“库存已更新，计划需要刷新”
```

不要自动修改 `PlanResponse` 的 `missingCount`，因为那会把原始计划和实时观察混为一谈。用户点击“刷新计划”或重新打开计划时，再生成新的权威缺口。

### 8.3 从 JEI 反查合成树缺口

可以增加一个低成本交互：

- JEI 物品悬停时，如果它属于当前合成树缺口，tooltip 增加“当前计划还缺 X 个”。
- 点击 JEI 物品时，定位合成树中的第一个匹配节点。
- 按 F 填充 RS 终端搜索栏时，仍填充物品名；如果当前计划存在缺口，可以同时将该物品加入“待补充材料”列表或打开对应节点。

第一版不建议做自动跳转和自动改计划，只做 tooltip 和颜色标记，降低输入事件与屏幕状态耦合。

## 9. JEI 和 RS Grid 的显示建议

### JEI

新增独立 Mixin：

```text
mixin/jei/IngredientListRendererAvailabilityMixin.java
```

职责：

- 只遍历 JEI 已经要绘制的物品槽位。
- 只查询客户端缓存。
- 绘制数量、`+`、缺口标记或 tooltip 扩展。
- 不执行网络请求和递归计算。

建议避免覆盖 JEI 原生数量文字。可采用：

- 右下角：RS/BD 数量，沿用 ExtendedAE Plus 的位置和阴影，缩放提高到 0.7 倍。
- 数量旁或右上角：小型 `+` 表示可递归合成。
- 左上角：红色计划缺口，例如 `-44`；与放大后的库存数字分处两个角，避免重叠。

如果空间不足，优先保留原生数量和 tooltip，缺口只放入 tooltip。

### RS Grid

RS Grid 已有 `ItemGridStackRenderMixin` 和 `GridItemRenderContext`。这里不应重复绘制 RS 原生数量，只绘制：

- 可递归合成标记。
- 当前计划缺口标记（仅计划上下文有效时）。

RS Grid 终端对应的是当前网络，状态必须优先使用当前网络会话的缓存，不能使用旧 JEI 页面残留数据。

## 10. 实施阶段

### 阶段一：RS 库存数量移植

范围：

- `StorageSession.snapshotItems()` 服务端快照。
- S2C 完整/增量同步。
- 客户端 `JeiItemNetworkCache`。
- JEI 数量覆盖层。
- 登出、换世界、断网清理。

验收：

- JEI 数量和 RS Grid 一致。
- NBT 物品不串数量。
- 网络断开后不显示旧数量。
- 大型 JEI 列表无明显每帧开销。

### 阶段二：递归可合成标记

范围：

- 按物品递归可行性请求。
- 服务端复用递归规划器。
- 客户端短期缓存、ticket 和限流。
- JEI `Craft`/`+` 标记。

验收：

- 无库存但可递归合成的物品能显示标记。
- 深度、步骤、机器或超时失败不会被误判为可合成。
- 打开 JEI 不会导致递归规划器持续满载。

### 阶段三：合成树缺口联动

范围：

- `ActiveCraftPlanContext`。
- `PlanResponse.materials()` 到 `StorageItemKey` 的索引。
- JEI tooltip 显示 `needed/available/missing`。
- RS Grid 缺口标记。
- 库存 revision 变化提示计划过期。

验收：

- `storedAmount` 和 `missingAmount` 不混淆。
- 计划关闭后 JEI 仍保留最近一次缺口；新计划覆盖，切换存储网络或退出服务器时清除。
- 实时库存变化后不会篡改旧计划数据。
- NBT 和标签材料的回退规则与合成树一致。

## 11. 主要风险与对应措施

| 风险 | 后果 | 措施 |
|---|---|---|
| JEI 每帧执行递归规划 | 客户端/服务端卡顿 | 递归只在服务端按需执行并缓存 |
| 只按物品 ID 匹配 | NBT 物品数量串线 | 使用 `StorageItemKey`/`IngredientKey` 精确匹配 |
| 把缺口当库存 | 数字含义错误 | 分开 `storedAmount` 与 `missingAmount` |
| 计划快照过期 | 显示旧缺口 | 引入库存 revision 和 plan revision |
| 递归失败被当成不可合成 | 用户误以为没有配方 | 区分 `MISSING`、`UNRESOLVED`、`TIMEOUT` |
| S2C 包过大 | 网络异常或解码失败 | 条目数和字节大小双重分块，decode 限制上限 |
| JEI/RS 原生文字重叠 | UI 难以阅读 | 只在固定角落绘制，并优先使用 tooltip |

## 12. 最终推荐方案

最终采用以下职责划分：

```text
StorageSession / StorageSnapshot
    负责：真实 RS 库存

JeiInventorySyncManager
    负责：库存快照和增量同步

RecursiveAvailabilityService
    负责：递归可合成性和递归缺口计算

ActiveCraftPlanContext
    负责：当前合成树的 needed/available/missing

ClientItemNetworkCache
    负责：客户端只读状态缓存

JEI / RS Grid Overlay
    负责：显示，不计算、不修改权威数据
```

最重要的数量关系是：

```text
真实库存 = storedAmount
计划总需求 = needed
计划快照库存 = plannedAvailable
计划净缺口 = missingAmount
```

推荐的首版用户界面：

```text
JEI 物品：20+
tooltip：库存 20；当前计划需求 64；还缺 44
```

其中 `20` 来自 RS 实时库存，`+` 来自递归可合成状态，`44` 来自当前合成树的 `missingCount`。三者来源和生命周期分离，既能准确表达含义，也能避免递归计算拖慢 JEI。

## 13. 建议的下一步

按阶段一开始实现最稳妥：先完成 RS 库存快照到 JEI 的显示，再接入递归可合成状态，最后把 `PlanResponse.materials()` 接入当前合成树上下文。这样每个阶段都能独立验证，出现问题时也容易定位是同步、递归规划还是 UI 合并造成的。
