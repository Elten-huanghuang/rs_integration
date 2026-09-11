# RS 专属虚空升级研究方案

## 1. 目标

为 Refined Storage 设计一个专属的虚空升级，用于销毁 RS 网络中后续发生变化、且匹配筛选规则的物品。

该升级的放置位置固定为 RS 终端旁边的四个筛选槽位，位置和 `refinedstorage:filter` 完全一致。

本阶段只确定实现方案，不编写功能代码。

## 2. 已确认的原生结构

### 2.1 RS Filter

RS 1.12.4 的 `refinedstorage:filter` 由以下结构组成：

- 每个终端有 4 个 Filter 槽位。
- 每个 Filter 物品内部有 27 个物品配置槽位。
- Filter 的主要 NBT 字段包括：
  - `Compare`
  - `Mode`
  - `ModFilter`
  - `Name`
  - `Icon`
  - `Type`
- `Compare` 是位掩码：
  - `1`：比较 NBT/标签数据。
  - `2`：比较堆叠数量。
- Filter 支持多个物品配置，也支持嵌套 Filter。
- RS 原生 Filter 可以按照物品和 Mod 过滤，但没有适合本需求的标签浏览器。

相关类：

```text
com.refinedmods.refinedstorage.item.FilterItem
com.refinedmods.refinedstorage.inventory.item.FilterItemHandler
com.refinedmods.refinedstorage.inventory.item.ConfiguredItemsInFilterItemHandler
com.refinedmods.refinedstorage.apiimpl.util.ItemFilter
com.refinedmods.refinedstorage.apiimpl.network.node.GridNetworkNode
```

### 2.2 Sophisticated Backpacks Void Upgrade

虚空升级的实际实现位于 Sophisticated Core，而不是 Backpacks 本体：

```text
net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper
net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic
net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogicBase
```

它的几个可借鉴点：

- 物品、标签、Mod 三种匹配模式独立存在。
- 支持白名单和黑名单。
- 支持匹配或忽略 NBT。
- 物品发生变化时只记录变化的位置。
- 后续再处理记录到的变化，不在安装升级时扫描全部内容。

RS 版本不应直接依赖 Sophisticated Core，只借鉴这些交互和时序设计。

## 3. 放置位置和作用范围

### 3.1 槽位位置

虚空升级物品应放入 RS Grid/终端节点原本用于 Filter 的四个槽位：

```text
RS 终端
└── 四个 Filter 槽位
    ├── 虚空升级 1
    ├── 虚空升级 2
    ├── 虚空升级 3
    └── 虚空升级 4
```

普通 `refinedstorage:filter` 和 RS 虚空升级可以共存。两者的职责不同：

- 普通 Filter：继续控制终端显示的分类、标签页或筛选视图。
- RS 虚空升级：提供销毁规则。

### 3.2 终端配置与网络处理分离

`GridNetworkNode` 的四个槽位属于具体终端，但销毁目标是 RS 网络中的物品。因此不能让每个终端直接独立监听并抽取，否则多个终端可能：

- 重复注册同一个网络缓存监听器。
- 对同一个变化重复处理。
- 终端数量增加后造成额外性能开销。

推荐采用两层结构：

```text
终端节点的四个槽位
        │
        ▼
网络级 Void Coordinator
        │
        ▼
INetwork.getItemStorageCache().addListener(...)
```

同一个 RS 网络只保留一个协调器。协调器收集该网络所有已连接终端上的虚空升级配置，并在终端连接、断开或配置改变时重建规则索引。

### 3.3 槽位配置的生命周期

- 终端连接到网络时，读取四个槽位中的虚空升级。
- 终端断开网络时，移除该终端的规则。
- 网络拆分时，旧协调器注销监听器，新网络分别建立自己的协调器。
- 终端卸载时不能遗留静态全局监听器。
- 配置保存于终端节点 NBT，而不是保存于临时内存对象。

## 4. 触发语义

### 4.1 不处理安装前的库存

安装虚空升级时：

- 不扫描当前 RS 物品列表。
- 不销毁已经存在的匹配物品。
- 不因为打开或关闭终端而触发处理。

只有升级安装之后，RS 缓存出现新的正向变化时才处理。

### 4.2 只处理增加量

RS 的缓存监听器提供：

```text
StackListResult.getStack()
StackListResult.getChange()
```

规则应使用 `getChange()` 作为数量变化量：

```text
已有 100 个钻石
后来增加 5 个钻石
只销毁新增的 5 个
```

不能把 `getStack().getCount()` 当作变化量。

### 4.3 自己的销毁不能再次触发规则

执行抽取后，RS 缓存会产生负向变化。协调器只处理正向变化，因此销毁动作不会递归触发自己。

另外仍应保留一个重入保护，防止不同 RS 存储实现的回调时序造成重复处理。

### 4.4 建议延后一 Tick 执行

缓存变化回调发生在 RS 存储操作过程中。建议回调只把变化放入队列，下一 Tick 再执行抽取：

```text
RS 插入
  -> 缓存产生正向变化
  -> 协调器入队
  -> 下一 Tick 批量匹配
  -> network.extractItem(..., Action.PERFORM)
```

这样可以降低缓存回调重入、批量合成和多个存储后端之间的时序风险。

## 5. 筛选规则设计

### 5.1 三种独立规则类型

每条规则必须明确类型，不让一个模糊输入框承担所有逻辑：

#### 精确物品

从以下来源选择物品：

- RS 当前物品列表。
- JEI 物品列表（可选）。
- 玩家手持物品。
- 终端内已有物品。

精确物品规则可单独设置是否匹配 NBT。

#### 标签

从已加载的物品标签注册表中选择，例如：

```text
forge:armors/chest
forge:armors/legs
minecraft:trimmable_armor
```

标签名称和实际存在情况取决于当前整合包，界面应显示注册表中的真实标签，不应硬编码中文标签名称。

#### Mod

从已加载的 Mod 列表选择命名空间，例如：

```text
minecraft
refinedstorage
create
some_mod
```

不要求玩家手动输入 Mod ID。

### 5.2 白名单和黑名单

建议使用明确的顶层模式：

- `销毁匹配项`：匹配规则的物品会被销毁。
- `保留匹配项`：匹配规则的物品不会被销毁，其他物品才会被销毁。

为了避免误清空网络，空规则时应默认不执行任何销毁，而不是把空白名单解释成“匹配全部”。

### 5.3 多选和逻辑关系

推荐支持两层逻辑：

```text
规则组
├── 精确物品：腐肉、蜘蛛眼
├── 标签：forge:armors/chest
└── Mod：某个 Mod

组内关系：任意匹配 / 全部匹配
顶层关系：销毁匹配项 / 保留匹配项
```

第一版可以先实现“任意匹配”，但数据模型应预留“全部匹配”，避免后续重做存档格式。

### 5.4 NBT 选项

建议默认忽略 NBT：

- 精确物品默认按物品 ID 匹配。
- 启用“匹配 NBT”后才区分附魔、组件数据、容器内容等。
- 标签和 Mod 规则天然忽略 NBT。

不建议第一版增加“匹配堆叠数量”，因为虚空升级的数量概念是变化量，而不是物品身份的一部分。

## 6. 智能选择界面

每个虚空升级物品右键打开独立配置界面，界面应由选择框和可视化槽位组成，而不是只提供文本输入。

推荐布局：

```text
┌────────────────────────────────────┐
│ 模式：销毁匹配项   规则关系：任意   │
│                                    │
│ [添加精确物品] [添加标签] [添加 Mod] │
│                                    │
│ 精确物品规则                        │
│ [物品图标] 钻石       [忽略 NBT]    │
│ [物品图标] 附魔钻石   [匹配 NBT]    │
│                                    │
│ 标签规则                            │
│ [forge:armors/chest]                │
│                                    │
│ Mod 规则                            │
│ [某个 Mod ▼]                        │
└────────────────────────────────────┘
```

选择器应具备：

- 搜索框。
- 物品图标和悬浮提示。
- 标签数量和来源显示。
- Mod 名称、Mod ID 和图标显示。
- 删除单条规则，而不是清空全部配置。
- 当前配置预览。

### 6.1 名称片段规则

第四类规则使用玩家填写的物品名称片段做模糊匹配。例如填写“胸甲”时，匹配显示名称中包含“胸甲”的物品。

- 不区分英文字母大小写。
- 只读取物品显示名称，不读取 Tooltip、附魔说明、属性或模组说明文字。
- 名称规则与精确物品、标签、Mod 规则独立，并继续采用多条规则 OR 匹配。
- 不支持正则表达式，避免复杂表达式造成性能和误销毁风险。
- 旧版本已经保存的头盔、胸甲、护腿和靴子装备槽规则继续兼容读取，但不再从新界面创建。

## 7. 性能方案

### 7.1 不轮询整个网络

禁止每 Tick 遍历：

```text
network.getItemStorageCache().getList().getStacks()
```

正确方式是注册 `IStorageCacheListener`，只处理 RS 已经报告的变化。

### 7.2 规则索引

配置改变后构建轻量索引：

- 精确物品：按物品 ID 索引。
- Mod：按 ResourceLocation 命名空间索引。
- 标签：缓存 `TagKey<Item>`。
- 名称：预先归一化为小写片段，仅在物品变化时检查显示名称。

运行时先做快速候选判断，只有命中候选后才执行完整的 NBT/标签比较。

### 7.3 批量和限流

- `onChangedBulk` 中合并相同物品的变化。
- 同一 Tick 对同一物品只执行一次抽取。
- 每 Tick 限制处理的物品种类和总数量。
- 超出预算的项目留到后续 Tick。
- 抽取时使用一次批量 `extractItem`，不要按单个物品循环抽取。

### 7.4 不要异步操作 RS 存储

RS 的网络缓存和存储后端状态只能在服务端主线程安全操作。可以异步准备规则索引，但不能异步调用：

```text
INetwork.insertItem
INetwork.extractItem
IStorageCache.getList
IStorageCache.add/remove
```

## 8. 权限、能耗和安全默认值

- 配置权限应遵循 RS 终端和网络安全权限。
- 虚空节点激活时可以增加固定 RS 能耗。
- 网络未运行时不执行销毁。
- 无规则时不执行销毁。
- 新放入的虚空升级默认处于“未武装”或空规则状态。
- 配置修改不追溯处理旧库存。
- 终端被拆除、网络断开或节点失效时立即移除监听。

## 9. 推荐实现顺序

### 第一阶段

- 终端四槽位识别 RS 虚空升级。
- 规则保存和读取。
- 精确物品多选。
- Mod 选择。
- 标签选择。
- 忽略 NBT。
- 白名单/黑名单。
- 只处理正向数量变化。
- 不处理安装前库存。

### 第二阶段

- 规则组的任意/全部关系。
- 名称片段模糊匹配。
- 终端之间的规则汇总和预览。
- 更完善的批量和限流统计。
- 最近销毁记录。

### 暂不建议加入

- 安装时自动清空旧库存。
- 每 Tick 全量扫描网络。
- 复杂的正则表达式匹配。
- 依赖 Sophisticated Core 的升级实现。
- 直接修改 RS 原生 Filter 的语义。

## 10. 最终建议

最终实现应采用：

```text
四个终端 Filter 槽位
        ↓
每个槽位一个 RS 虚空升级
        ↓
网络级协调器合并所有终端规则
        ↓
IStorageCacheListener 监听正向变化
        ↓
下一 Tick 批量匹配并抽取销毁
```

这样既满足“位置和 `refinedstorage:filter` 一样”，也满足：

- 不立即影响已有物品。
- 只处理发生变化的物品。
- 支持忽略 NBT。
- 支持物品、标签、Mod 多选。
- 不依赖玩家手动填写 ID。
- 不通过轮询整个 RS 网络造成卡顿。
