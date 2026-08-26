# BeyondDimensions 原生共振盘方案

## 目标

把共振盘做成 BeyondDimensions 原生功能。新实现不依赖 Refined Storage 的磁盘、网络或存储管理器；旧 RS 共振盘只作为一次性迁移来源，不参与新系统运行。

本方案只描述设计和资源方向，不改变现有代码或旧共振盘行为。

## 核心原则

1. 新共振盘只有一个数据后端：BeyondDimensions 原生后端。
2. 共振盘数据归磁盘自身所有，不归 RS，也不直接混入 BD 普通 `UnifiedStorage`。
3. BD 网络只提供绑定、权限、网络名称和生命周期信息。
4. 新盘与旧盘使用不同物品身份；不在运行时做 RS/BD 自动切换。
5. 旧 RS 盘只能通过显式迁移转换为新 BD 盘。

## 物品身份

建议新增物品 ID：

```text
rs_integration:dimensional_resonance_disk
```

如果以后拆分为独立附属模组，物品 ID 可以迁移为：

```text
resonance_bd:dimensional_resonance_disk
```

现有 `rs_integration:resonance_storage_disk` 保持不变，只用于旧存档和旧 RS 功能。

## 数据模型

新盘物品 NBT 只保存路由信息：

```text
BDResonance
  DiskUUID: UUID
  NetId: int
  Schema: int
```

盘内数据保存到 RSI/BD 专用 `SavedData`，按磁盘 UUID 索引：

```text
BDResonanceDiskData
  diskUuid
  boundNetId
  owner
  abilityMask
  revision
  slots[36]
```

每个槽位保存完整 `ItemStack` NBT、数量、逻辑槽位和数据版本。非堆叠装备、NBT 变体和能力物品必须保持独立身份。

选择“按磁盘 UUID 保存”而不是“按网络 ID 保存”，是为了让网络销毁后仍可恢复磁盘内容。网络只是访问边界，不是数据所有者。

## BeyondDimensions 绑定

- 未绑定新盘右键当前主网络：绑定。
- 已绑定新盘右键：打开新的共振背包。
- 潜行右键：解绑，并弹出确认提示。
- 绑定和解绑只允许网络所有者或管理员。
- 普通成员能否使用，遵循 BD 网络访问权限。
- 使用 `NetId` 作为网络引用；不要求新盘继承 BD 的 `NetedItem`。
- 监听 `DimensionsNetEvent.Destroyed`，网络销毁后只清除绑定，保留盘内数据。

## 与 BD 普通网络的边界

共振盘内容不镜像到 `DimensionsNet.getUnifiedStorage()`，也不显示为 BD 普通网络库存。共振盘继续承担特殊被动库存和共振背包职责。

这样可以避免：

- BD 普通仓库和共振盘出现两份库存；
- 普通网络提取与共振盘提取互相扣错；
- NBT 变体被 BD 的聚合存储错误合并；
- 共振被动效果看到的数量与网络界面不一致。

如果以后需要“BD 网络可见的共振存储单元”，应作为另一个产品单独设计，不与本方案的共振盘混用。

## 旧 RS 盘迁移

迁移是显式的一次性操作：

1. 读取旧 RS 盘的 36 个逻辑槽位和能力掩码。
2. 创建新的 `DiskUUID` 和 BD 数据记录。
3. 写入新 `SavedData`。
4. 校验所有槽位、NBT、能力和容量。
5. 生成新的 `dimensional_resonance_disk`。
6. 原 RS 盘保持不变，直到玩家确认清理。

建议迁移状态为 `NONE`、`PREPARED`、`COMMITTED`、`FAILED`，服务器重启后可以继续或回滚，不允许出现半迁移盘。

新 BD 盘运行时不反向访问 RS。RS 只存在于迁移工具的可选读取路径。

## 实现分层

```text
resonance.bd.item
resonance.bd.data
resonance.bd.binding
resonance.bd.menu
resonance.bd.passive
resonance.bd.migration
```

需要复用的是槽位规则、能力定义和被动效果契约；不复用 RS 磁盘包装类或 RS 存储管理器。

## 被动效果与兼容层共用

RS 盘和 BD 盘可以共用同一套被动效果、能力解锁和第三方兼容逻辑，但不能让这些逻辑直接依赖某一个后端的类。实现时增加后端中立的 `ResonanceStorageView`/`ResonanceStorageResolver` 边界：

```text
兼容 Mixin / 被动效果 / 能力服务
                |
      ResonanceStorageView
          /              \
 RS adapter                  BD adapter
ResonanceDiskWrapper       BDResonanceDiskRecord
```

统一视图至少提供：完整槽位快照、精确 NBT 身份、槽位原子变更、精确提取/插入、能力掩码、内容 revision 和后端状态。`TickSimulator`、被动效果扫描、物品查找、数量统计和能力判断只调用这个视图，因此以后新增兼容只写一份。

现有 `RSInventoryBridge` 保留为旧 RS 调用方的兼容外观；新的公共入口应改为 `ResonanceInventoryBridge`。它可以同时返回玩家当前有权限访问的 RS 盘和 BD 盘：

- 查询类操作合并两个视图的快照；相同物品不会因为后端不同而丢失数量。
- 提取类操作按明确的后端优先级逐个执行，并在不足时回滚已提取部分，禁止静默跨网络扣错物品。
- 能力掩码按位或合并；同一能力在两个盘中存在时只执行一次被动效果。
- 被动 `inventoryTick` 按每个磁盘的 revision 独立缓存，任何一个盘的变更不会污染另一个盘。

RS 和 BD 同时存在时，二者是两个独立库存源，但兼容层只产生一个逻辑结果。RS 盘的物品 ID、`ResonanceDiskWrapper`、RS 存储管理器和旧背包路径保持不变；BD 盘只通过 BD adapter 接入。未安装 BD 时不会加载 BD 类，也不会改变现有 RS 运行路径。

BeyondDimensions 相关类应放在可选集成边界内，确保未安装 BD 时不会提前链接 BD 类。

## 界面

共振背包保留 36 槽布局，增加顶部状态栏：

```text
共振背包
网络：#12 / 自定义网络名
状态：已连接 / 网络不可用 / 迁移中
能力：能力图标
容量：当前数量 / 2304
```

Tooltip 显示磁盘 UUID 的短标识、网络名称、绑定状态、能力状态和错误原因。

## 图标资源规格

新图标不覆盖旧 RS 图标，建议放在独立设计目录，确认后再复制到正式资源目录：

```text
dimensional_resonance_disk.png       32x32
dimensional_resonance_disk_bound.png 32x32
dimensional_resonance_disk_error.png 32x32
gui/resonance/bd_badge.png            16x16
gui/resonance/migration.png           16x16
gui/resonance/offline.png             16x16
gui/resonance/conflict.png            16x16
gui/resonance/ability.png             16x16
```

视觉方向：保留磁盘轮廓；中心使用 BeyondDimensions 的青蓝时空裂隙；外圈使用紫色共振环。已绑定为完整青蓝环，未绑定为灰白断环，网络失效为暗红断环，迁移中为琥珀环形标记。

不建议一开始使用物品栏动画。动画只放在 GUI 状态图标中，避免小尺寸物品图标闪烁。

### BeyondDimensions 侧栏按钮

侧栏按钮按 BD 原生资源分为两层：

- 图案层为透明 `16x16` PNG，用于绘制 RS 原有图案；
- 背景层为完整 `23x26` 的 `left_tab/right_tab` PNG，不能缩成 `23x16`，也不叠加 `slot_button`。

左侧 tab 的图案绘制偏移为 `+5,+4`，右侧 tab 为 `+3,+4`。hover 版本只替换 tab 背景为 BD 槽位使用的蓝色反馈，图案像素保持不变。

当前预览资源位于 `docs/assets/resonance_bd_tabs/`：

```text
resonance_backpack_bd_icon_16x16.png
machine_center_bd_icon_16x16.png
resonance_backpack_bd_tab.png
resonance_backpack_bd_tab_hover.png
machine_center_bd_tab.png
machine_center_bd_tab_hover.png
```

## 图标设计稿

当前目录中的 `docs/assets/resonance_bd/` 已放入 32x32 PNG 预览：

```text
dimensional_resonance_disk.png
dimensional_resonance_disk_bound.png
dimensional_resonance_disk_error.png
dimensional_resonance_disk_migrating.png
resonance_disk_icon_sheet.png
```

这些是独立设计稿，不会覆盖现有 RS 共振盘，也尚未接入物品模型或注册表。

## 验收清单

- BD 网络所有者、管理员、成员的权限边界明确。
- 36 槽位保存/加载后内容完全一致。
- NBT 变体和非堆叠物品不会合并。
- 同一网络不会激活多个新共振盘。
- 网络销毁不会删除盘内内容。
- 新盘在没有 RS 时可独立运行。
- 旧 RS 盘迁移中断可恢复。
- 客户端不会直接读取服务端 `SavedData`。
- 新图标与旧 RS 图标在物品栏中可一眼区分。
