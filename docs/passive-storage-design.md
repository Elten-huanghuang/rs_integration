# RS 网络被动效果系统 — 设计方案 v2

## 核心设计反转

**v1 错误假设**："RS 里所有物品都生效" → 破坏了 "不想生效 → 丢 RS" 这个基本操作。

**v2 正确模型**：

```
RS 普通磁盘 = 仓库 = inert（不生效）
RS 共振磁盘 = "饰品栏扩展" = 只有这里的物品生效
```

玩家通过**共振盘**精确控制哪些物品处于激活态。不想让某个东西生效？别放进共振盘就行了。

---

## RS 存储架构（关键发现）

```
INetwork
 └── ItemStorageCache
      ├── storages: CopyOnWriteArrayList<IStorage<ItemStack>>  ← 每个磁盘是独立条目
      └── list: IStackList<ItemStack>                          ← 合并视图（Grid 用）
```

`getStorages()` 返回的是**独立存储源列表**。每个 `ItemStorageDisk` 有自己的 UUID、容量和内部 `Multimap<Item, ItemStack>`。

这意味着 PassiveEffectEngine 不需要遍历整个 RS 网络。只需：

```java
for (IStorage<ItemStack> storage : cache.getStorages()) {
    if (storage instanceof ResonanceDiskWrapper) {
        // 只遍历共振盘里的物品（几十个，不是几千个）
        scanAndTick(storage.getStacks());
    }
}
```

**扫描量：5000+ → 50 以内，TPS 开销彻底清零。**

---

## 共振盘（Resonance Disk）

### 概念

一种新的 RS 存储盘类型。外观与普通盘有区分（紫色边框/粒子）。放入磁盘驱动器后行为与普通盘完全一样，但：

| 特性 | 普通盘 | 共振盘 |
|---|---|---|
| 存储物品 | 是 | 是 |
| 接受自动路由 (Grid/管道) | 是 | **否 — insert() 一律拒绝** |
| 接受手动放入 (背包 UI) | — | **是 — manualInsert() + 被动物品过滤** |
| 被动效果扫描 | 否 | **是 — 只有它的内容被扫描** |
| RS Grid 可见 | 是 | 是 |
| 磁盘操作器支持 | 是 | 是 |

### 手动放入 — 拒绝自动路由

共振盘**不接受 RS 网络的自动路由**。物品不能通过 Grid 界面 shift-click、网络管道、或其他自动方式进入共振盘。玩家必须**打开共振背包界面，手动将物品拖入槽位**。

```java
public ItemStack insert(ItemStack stack, int size, Action action) {
    return stack; // 一律拒绝：共振盘不接受自动路由
}
```

这意味着：
- RS Grid shift-click 物品 → 走 `network.insertItem()` → 路由到共振盘 → **拒绝**，物品留在 Grid
- 网络管道/输入总线 → `disk.insert()` → **拒绝**
- 共振背包 UI 拖入物品 → 走专用 `manualInsert()` 通道 → **接受**

**手动放入的实现**：

```java
// ResonanceDiskWrapper 额外暴露手动插入通道
public ItemStack manualInsert(ItemStack stack, int size, Action action) {
    if (!PassiveRegistry.isPassiveItem(stack)) {
        return stack; // 仍然过滤：非被动物品不允许进入共振盘
    }
    return delegate.insert(stack, size, action);
}
```

背包 Container 的 `quickMoveStack()` 和槽位变更回调调用 `manualInsert()` 而非 `insert()`，确保只有手动操作能放入物品。

**设计理由**：
- 玩家对共振盘内容有**完全控制权**——不会出现"东西什么时候跑进去的？"
- 自动路由可能导致共振盘被单一物品填满（如无限刷怪塔的骨头）
- 手动放入是**有意识的决策**，符合共振盘作为"精选被动配置"的定位
- 仍然过滤非被动物品：手动放入也不能放无效物品，防止误操作

`PassiveRegistry` 由三来源合并：
1. Phase 1 属性扫描发现的物品（运行时动态）
2. Phase 2 JSON 白名单物品
3. Phase 3 事件 Mixin 覆盖的物品

### 一网络一盘 — 互斥锁

一个 RS 网络中**只能存在一个共振盘**。插入第二个共振盘时，服务端拒绝并提示玩家。

**检测时机**：磁盘驱动器 `DiskDriveNetworkNode` 的 inventory change listener → `StackUtils.createStorages()` 遍历所有磁盘槽位时：

```java
// StackUtils.createStorages() 或等效插入点
int resonanceCount = 0;
for (IStorageDisk<?> disk : itemDisks) {
    if (disk instanceof ResonanceDiskWrapper) resonanceCount++;
}
if (resonanceCount > 1) {
    // 拒绝第二个共振盘，物品弹回玩家背包
    // 发送提示消息："此网络已有一个共振盘，请先移除再插入新的"
}
```

**为什么不是插入时拦截**：共振盘作为普通物品放入磁盘驱动器槽位时，系统还不知道这是"共振盘"——`ResonanceDiskWrapper` 只有在 `StackUtils.createStorages()` 通过 `getFactoryId()` 加载后才会被识别。因此检测点在**磁盘加载后**而非物品放入时。

**回退策略**：检测到重复时，后插入的共振盘槽位返回 `null`（等同于空槽），物品留在驱动器槽位中可被取出。已存在的共振盘不受影响，继续正常工作。

**客户端提示**：重复插入时，玩家收到红色文字提示："此 RS 网络已绑定共振盘，同一网络只能使用一个共振盘。"

### 实现方式

两种路线，需要评估后选择：

**路线 A：Wrapper 装饰器（推荐先试）**

```java
public class ResonanceDiskWrapper implements IStorageDisk<ItemStack> {
    private final IStorageDisk<ItemStack> delegate; // 普通 ItemStorageDisk

    @Override
    public ItemStack insert(ItemStack stack, int size, Action action) {
        if (!PassiveRegistry.isPassiveItem(stack)) return stack;
        return delegate.insert(stack, size, action);
    }

    // 其余方法全部委托给 delegate
}
```

- 不继承 RS 内部类，不碰 `Multimap` 细节
- 创建一个普通 `ItemStorageDisk` 实例，包一层 wrapper
- Wrapper 在 `insert()` 上加过滤，其余全透传
- RS 升级时不易 break

**路线 B：注册自定义 IStorageDiskFactory**

- 通过 RS 的 `IStorageDiskRegistry` 注册新磁盘类型
- 共振盘有独立的 `ResourceLocation` factory ID（`rsi:resonance`）
- NBT 持久化时写 `factoryId: "rsi:resonance"`
- 更深度集成，但需要确认 RS API 是否开放 registry

**建议**：先用路线 A 验证逻辑，稳定后再评估是否迁移到路线 B。

### 直接磁盘级 extract/insert — 解决精准消耗

对于 `mutates=true` 的物品（Potion Charm 耐久、Nine Sword Book NBT 追赶），直接操作共振盘实例：

```java
IStorageDisk<ItemStack> disk = findResonanceDisk(network);

// 从共振盘提取，不经过网络路由
ItemStack real = disk.extract(template, 1, PERFORM);
if (!real.isEmpty()) {
    real.getItem().inventoryTick(real, level, player, -1, false);

    if (!real.isEmpty()) { // 耐久未耗尽
        disk.insert(real, real.getCount(), PERFORM);
        // ↑ 触发 IStorageDiskListener.onChanged() → cache 自动同步
    }
}
```

**这解决了 v1 的致命盲区**：网络里有 2 个 Potion Charm（一个普通盘、一个共振盘）时，`network.extractItem()` 可能错误扣除普通盘的那个。直接操作磁盘实例确保了只动共振盘内的物品。

---

## 深水区解决方案

### 1. 缓存脱步（Cache Desync）

**问题**：直接修改磁盘内部状态不会通知 `ItemStorageCache`。

**方案**：`mutates=true` 物品走 `disk.extract()` → tick → `disk.insert()` 路径。`ItemStorageDisk` 内部每次 `insert/extract` 都会调 `onChanged()` → `IStorageDiskListener.onChanged()` → `ItemStorageCache.add/remove()`，缓存自动同步。

```java
// ItemStorageDisk 内部（RS 源码）：
public ItemStack insert(ItemStack stack, int size, Action action) {
    // ... 修改内部 Multimap ...
    onChanged(); // → listener.onChanged() → cache 更新
    return result;
}
```

**结论**：只要走 `extract/insert` API（而非直接操作 `getRawStacks()`），缓存脱步不会发生。

### 2. 路由优先级（鸠占鹊巢）

**问题**：共振盘被非被动物品填满。

**方案**：共振盘 `insert()` 硬性拒绝非被动物品（见上文）。RS 原生路由（优先级 + 磁盘满 → 溢出到下一个盘）会自然将非被动物品路由到普通盘。

额外措施：共振盘在 RS 磁盘驱动器中的默认优先级设为最低（`-1`），确保普通物品优先存入普通盘。只有被动物品在普通盘满或玩家手动设置后才会进入共振盘。

### 3. NBT 合并（Stacking Rules）

**问题**：相同物品不同 NBT（耐久、杀敌数）被错误合并。

**分析**：`ItemStack` 的 equals/hashCode 包含 NBT。RS 的 `Multimap<Item, ItemStack>` 以 `ItemStack` 为值，两个 NBT 不同的同种物品不会合并。RS 的 `IComparer` 系统控制合并粒度，默认 `COMPARE_NBT` 确保 NBT 不同的物品分开存储。

**结论**：RS 原生机制已正确处理。共振盘不需要特殊逻辑。

---

## RS 磁盘生命周期（SPI 逆向分析）

### 关键接口

| 接口 | 作用 |
|---|---|
| `IStorageDisk<T>` | 磁盘实例。extends `IStorage<T>`。有 `getFactoryId()`、`writeToNbt()`、`getCapacity()`、`getOwner()` |
| `IStorageDiskFactory<T>` | 工厂。`createFromNbt(level, nbt)` / `create(level, capacity, owner)` / `createDiskItem(disk, owner)` |
| `IStorageDiskRegistry` | 工厂注册表。`add(ResourceLocation, factory)` / `get(ResourceLocation)` |
| `IStorageDiskManager` | 磁盘持久化管理。`get(uuid)` / `set(uuid, disk)` / `getByStack(stack)` / `markForSaving()` |
| `IStorageDiskProvider` | 磁盘物品接口。`getId(stack)` / `setId(stack, uuid)` / `isValid(stack)` / `getCapacity(stack)` / `getType()` |

内置工厂 ID：`ItemStorageDiskFactory.ID = new ResourceLocation("refinedstorage", "item")`

### 磁盘创建（首次激活）

触发点：`StorageDiskItem.inventoryTick()` — 物品首次出现在玩家背包（服务端、无 NBT tag）时：

```java
// StorageDiskItem.inventoryTick() — RS 源码还原
public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
    if (level.isClientSide()) return;
    if (stack.hasTag()) return;          // 已初始化，跳过
    if (!(entity instanceof Player player)) return;

    UUID id = UUID.randomUUID();         // 分配 UUID
    IStorageDisk<ItemStack> disk = API.instance().createDefaultItemDisk(
        (ServerLevel) level, getCapacity(stack), player);

    API.instance().getStorageDiskManager((ServerLevel) level).set(id, disk);  // 注册
    API.instance().getStorageDiskManager((ServerLevel) level).markForSaving();
    setId(stack, id);                    // 写入物品 NBT
}
```

**关键**：磁盘物品首次进入背包时，`createDefaultItemDisk()` 硬编码使用 `ItemStorageDiskFactory`。共振盘物品需要 override 此方法，改用我们的 factory。

### 磁盘加载（放入驱动器时）

触发点：`DiskDriveNetworkNode` 的 inventory listener → `StackUtils.createStorages()`：

```java
// StackUtils.createStorages() — RS 源码还原
public static void createStorages(ServerLevel level, ItemStack stack, int slot,
        IStorageDisk<ItemStack>[] itemDisks, IStorageDisk<FluidStack>[] fluidDisks,
        Function<IStorageDisk<ItemStack>, IStorageDisk<ItemStack>> itemWrapper,  // 创建 ItemDriveWrapperStorageDisk
        Function<IStorageDisk<FluidStack>, IStorageDisk<FluidStack>> fluidWrapper) {

    if (stack.isEmpty()) { itemDisks[slot] = null; fluidDisks[slot] = null; return; }

    IStorageDisk disk = API.instance().getStorageDiskManager(level).getByStack(stack);

    if (disk != null) {
        StorageType type = ((IStorageDiskProvider) stack.getItem()).getType();
        if (type == StorageType.ITEM) itemDisks[slot] = itemWrapper.apply(disk);  // 包一层 ItemDriveWrapperStorageDisk
        else fluidDisks[slot] = fluidWrapper.apply(disk);
    } else {
        itemDisks[slot] = null; fluidDisks[slot] = null;
    }
}
```

`getByStack()` 内部：从物品 NBT 读 UUID → `get(uuid)` 查 manager 中已有磁盘。

### 完整 wrapper 链

普通盘放入驱动器后的层级结构：

```
ItemDriveWrapperStorageDisk     ← 驱动器上下文（priority, access type, filter）
  └── parent: ItemStorageDisk    ← 原生磁盘（Multimap<Item, ItemStack>）
```

共振盘放入驱动器后的层级结构：

```
ItemDriveWrapperStorageDisk     ← 驱动器上下文（同上，透明）
  └── parent: ResonanceDiskWrapper  ← insert 硬性过滤（只接受被动物品）
        └── delegate: ItemStorageDisk  ← 原生磁盘（存储 + 序列化）
```

每一层都实现 `IStorageDisk<ItemStack>`，调用链完整透传。`getFactoryId()` 沿链向上返回：`ResonanceDiskWrapper` → `"rsi:resonance"`；`ItemDriveWrapperStorageDisk` → 委托 parent。`StorageDiskManager` 存盘时取到的 factoryId 是 `"rsi:resonance"`，下次加载时路由到我们的工厂。

### 持久化格式

`StorageDiskManager` 以世界 save data 形式持久化：

```json
{
  "Disks": [
    {
      "DiskId": "<uuid>",
      "DiskType": "refinedstorage:item",   // 普通盘 — 或 "rsi:resonance"
      "DiskData": { "Version": 1, "Capacity": 1024, "Items": [...], "Owner": "<uuid>" }
    }
  ]
}
```

- `DiskType` 决定用哪个 `IStorageDiskFactory` 反序列化
- `DiskData` 是 `IStorageDisk.writeToNbt()` 的输出（对共振盘来说，和普通盘格式完全一致，因为 delegate 就是 `ItemStorageDisk`）
- 共振盘和普通盘的区别**仅在 `DiskType` 字段**

### 混合路线的实现要点

```java
// ResonanceDiskFactory — 注册到 "rsi:resonance"
public class ResonanceDiskFactory implements IStorageDiskFactory<ItemStack> {
    private final IStorageDiskFactory<ItemStack> rsFactory;

    public ResonanceDiskFactory() {
        this.rsFactory = API.instance().getStorageDiskRegistry()
            .get(ItemStorageDiskFactory.ID);  // "refinedstorage:item"
    }

    @Override
    public IStorageDisk<ItemStack> createFromNbt(ServerLevel level, CompoundTag nbt) {
        return new ResonanceDiskWrapper(rsFactory.createFromNbt(level, nbt));
    }

    @Override
    public IStorageDisk<ItemStack> create(ServerLevel level, int capacity, UUID owner) {
        return new ResonanceDiskWrapper(rsFactory.create(level, capacity, owner));
    }

    @Override
    public ItemStack createDiskItem(IStorageDisk<ItemStack> disk, UUID owner) {
        // 单一等级，直接创建共振盘物品实例
        ItemStack stack = new ItemStack(ModItems.RESONANCE_DISK.get());
        ((ResonanceDiskItem) stack.getItem()).setId(stack, owner);
        return stack;
    }
}
```

注册时机：`FMLCommonSetupEvent`（或 `enqueueWork` 中）：
```java
API.instance().getStorageDiskRegistry()
    .add(new ResourceLocation("rsi:resonance"), new ResonanceDiskFactory());
```

---

## 架构

```
RS Network ──→ PassiveEffectEngine ──→ getItemStorageCache().getStorages()
                                           │
                                           ├── 过滤出 ResonanceDiskWrapper 实例
                                           │
                                           └──→ 对每个共振盘:
                                                 │
                                                 ├── AttributeScanner (每 20t)
                                                 │   遍历盘内物品 → 提取属性 → transient modifier
                                                 │
                                                 ├── TickSimulator (每 1t)
                                                 │   遍历盘内物品 → 查白名单 → inventoryTick
                                                 │   │
                                                 │   ├── mutates=false → 快照 tick（只读）
                                                 │   └── mutates=true  → disk.extract → tick → disk.insert
                                                 │
                                                 └── ResonanceBackpackContainer (按需)
                                                      玩家打开背包 → 槽位 ↔ disk.insert/extract
```

六个组件，总量基本不变，但职责更清晰：

| 组件 | 说明 | 行数估计 |
|---|---|---|
| `ResonanceDiskWrapper` | 包装 `ItemStorageDisk`，insert 硬性过滤 + 透传 | ~40 行 |
| `AttributeScanner` | 遍历共振盘物品，提取属性，增量管理 transient modifier | ~100 行 |
| `TickSimulator` | JSON 白名单 + inventoryTick，直接磁盘级读写 | ~60 行 |
| `PassiveEffectEngine` | 串联调度 + 共振盘发现 + 网络解析 | ~80 行 |
| `PassiveRegistry` | 三来源合并（属性扫描 + JSON + 事件物品） | ~30 行 |
| `ResonanceBackpackContainer` | 基于 `IStorageDisk` 的容器，槽位 ↔ disk 双向同步 | ~100 行 |
| `ResonanceBackpackScreen` | 背包 GUI 渲染，背景纹理 + 槽位 + 状态栏 | ~80 行 |

### 共振盘发现

```java
public static List<IStorageDisk<ItemStack>> findResonanceDisks(INetwork network) {
    List<IStorageDisk<ItemStack>> disks = new ArrayList<>();
    var cache = network.getItemStorageCache();
    if (cache == null) return disks;

    for (IStorage<ItemStack> storage : cache.getStorages()) {
        if (storage instanceof ResonanceDiskWrapper resonanceDisk) {
            disks.add(resonanceDisk);
        }
    }
    return disks;
}
```

### 流程

```
每 1 tick (PlayerTickEvent):
  TickSimulator:
    1. 解析玩家绑定的 RS 网络
    2. 发现共振盘: findResonanceDisks(network)
    3. 对每个共振盘的白名单物品:
       a. mutates=false → 从盘 getStacks() 拿快照，调用 inventoryTick
       b. mutates=true  → disk.extract(1) → tick → disk.insert

每 20 tick (独立定时器):
  AttributeScanner:
    4. 发现共振盘
    5. 遍历盘内物品，提取 AttributeModifiers
    6. hash 比较 → 增量更新 transient modifiers
    7. 同步更新 PassiveRegistry（属性物品列表）

物品事件处理器（如 LivingHurtEvent）:
  → Phase 3 Mixin 介入
  → 将背包遍历替换为: findResonanceDisks(network) → getStacks() 遍历
  → 其他加成计算逻辑不变
```

---

## 共享背包（Shared Resonance Backpack）

### 概念

共振盘插入磁盘驱动器后，不但提供被动效果，还作为**整个网络的共享背包**。任何连接到该 RS 网络的玩家都可以打开这个背包，存取物品。视觉上它是一个**传统 RPG 背包界面**（而非 RS Grid 表格），背景纹理支持资源包换肤。

### 打开方式

| 方式 | 描述 |
|---|---|
| 右键磁盘驱动器 | 当驱动器内含共振盘时，右键弹出背包界面 |
| 快捷键 | 可配置的快捷键（默认无），直接打开已绑定网络的共振背包 |
| RS Grid 标签页 | Grid 右侧新增"共振"标签，与物品/流体标签并列，图标使用共振盘物品本身 |

**标签页图标**：RS 原生使用 ItemStack 渲染标签页图标，不需要单独绘制按钮纹理。共振盘物品（`resonance_storage_disk.png`，32×32）直接作为标签页图标使用，Minecraft GUI 自动缩放到 16×16 渲染。

### 容量与槽位

共振盘只有**一个等级**，容量和槽位布局与玩家背包完全一致：

| 属性 | 值 |
|------|-----|
| 容量 | 与玩家背包相同（4×9 = 36 槽位） |
| 布局 | 9 列 × 4 行（1 行快捷栏 + 3 行物品栏） |
| 设计理由 | 直接映射玩家背包结构，无需多等级管理 |

每个槽位对应共振盘存储中的一个物品 Stack。槽位区作为"共享背包"供网络内所有玩家存取。

### UI 布局

```
┌─────────────────────────────────────┐
│   共振背包 — "共享仓库"             │
│   ┌─────────────────────────────┐   │
│   │   ░░░░░░░░░░░░░░░░░░░░░░░   │   │
│   │   ░  ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ░   │   │
│   │   ░  ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ░   │   │  ← 物品栏 (3×9)
│   │   ░  ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ░   │   │
│   │   ░░░░░░░░░░░░░░░░░░░░░░░░░   │   │  ← 分隔线
│   │   ░  ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ⬜ ░   │   │  ← 快捷栏 (1×9)
│   │   ░░░░░░░░░░░░░░░░░░░░░░░░░   │   │
│   │         共振盘: 12/36          │   │  ← 容量状态
│   └─────────────────────────────┘   │
└─────────────────────────────────────┘
```

与玩家背包布局一致：物品栏在上（3 行），分隔线，快捷栏在下（1 行）。槽位索引对应：
- index 0-26：物品栏
- index 27-35：快捷栏
```

GUI 结构与玩家背包镜像：上半是共振盘内容（4×9=36 槽位 + 容量指示），下半是被动效果状态栏。不需要再嵌套一层玩家背包——打开共振背包就是看共振盘里的东西。

背包背景纹理：`textures/gui/resonance_backpack.png`（256×256，RGBA PNG），资源包在同路径覆盖即可换肤。

### 技术架构

```
客户端                             服务端
┌──────────────────┐    ┌─────────────────────────┐
│ ResonanceBackpack│    │ ResonanceBackpackContainer│
│ Screen           │◄───│                          │
│                  │    │  ┌─────────────────────┐ │
│ - 渲染背包纹理   │    │  │ IStorageDisk (共振盘) │ │
│ - 渲染物品槽位   │    │  │  ↓ insert/extract   │ │
│ - 显示效果列表   │    │  │ ResonanceDiskWrapper │ │
│                  │    │  └─────────────────────┘ │
└──────────────────┘    └─────────────────────────┘
```

**Container 设计要点**：

```java
public class ResonanceBackpackContainer extends AbstractContainerMenu {
    private final IStorageDisk<ItemStack> disk;
    private final INetwork network;

    // 打开时从共振盘加载当前物品到槽位
    private void loadFromDisk() {
        for (ItemStack stack : disk.getStacks()) {
            // 分配到 container slots
        }
    }

    // 槽位变更时写回共振盘
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // extract from slot → disk.insert()
        // OR disk.extract() → player inventory
    }
}
```

### 鼠标手势 — 滑动放入/取出

共振背包支持**滑动手势**批量操作，与 RS Grid 的 swipe-extract 行为一致：

**滑动放入**（玩家背包 → 共振盘）：
- 按住 Ctrl + 左键拖拽划过玩家背包槽位
- 划过的物品依次通过 `manualInsert()` 写入共振盘
- 被拒绝的物品（非被动）留在原位，已移入的从玩家背包消失

**滑动取出**（共振盘 → 玩家背包）：
- 按住 Ctrl + 左键拖拽划过共振盘槽位
- 划过的物品依次从共振盘 `extract()`，放入玩家背包
- 玩家背包满时停止，剩余物品留在共振盘

```java
// ResonanceBackpackScreen 中的滑动处理
if (Screen.hasControlDown() && button == 0) {
    if (isOverResonanceSlots(mouseX, mouseY)) {
        // 滑动取出：共振盘 → 玩家背包
        ItemStack extracted = disk.extract(slotStack, 1, Action.PERFORM);
        if (!extracted.isEmpty()) {
            player.addItem(extracted);
        }
    } else if (isOverPlayerInventory(mouseX, mouseY)) {
        // 滑动放入：玩家背包 → 共振盘（走 manualInsert）
        ItemStack taken = playerSlot.remove(1);
        ItemStack rejected = disk.manualInsert(taken, 1, Action.PERFORM);
        if (!rejected.isEmpty()) {
            playerSlot.add(rejected); // 被拒绝就放回去
        }
    }
}
```

**交互约定**：
- 与 RS Grid 的 Ctrl+左键拖拽行为完全一致，玩家无需学习新操作
- 滑动放入受 `manualInsert()` 的被动物品过滤约束
- 滑动过程显示计数提示：`"已移入 5 件 / 已取出 3 件"`

**关键差异 vs 普通容器**：
- 底层存储不是 `BlockEntity` 的 `IItemHandler`，而是 `IStorageDisk<ItemStack>`
- `disk.insert()` 触发硬性过滤（只接受被动物品），被拒绝时物品弹回玩家背包
- 多玩家同时操作：各自有独立 Container 实例，底层操作同一 disk，由 disk 内部锁保证一致性

### 纹理包换肤系统

#### 设计原理

背包背景纹理通过 **资源位置（ResourceLocation）** 加载。Minecraft 的资源包系统天然支持覆盖：资源包只需在同路径放置同名文件即可替换纹理。

```
默认纹理:
  src/main/resources/assets/rs_integration/textures/gui/resonance_backpack.png

资源包覆盖:
  <resource_pack>/assets/rs_integration/textures/gui/resonance_backpack.png
```

**不需要写任何自定义换肤代码** — 资源包的 `pack.mcmeta` + 文件覆盖即完成换肤。

#### 进阶：多彩纹理变体

如果后续需要多个内置纹理供玩家选择（如"皮革背包"、"虚空背包"等外观），可通过配置系统切换纹理路径：

```java
// ResonanceBackpackScreen 渲染时：
ResourceLocation texture = new ResourceLocation(
    ModConfig.backpackTextureNamespace,  // 默认 "rs_integration"
    "textures/gui/" + ModConfig.backpackTextureName + ".png"
);
// 示例路径：rs_integration:textures/gui/resonance_backpack.png
//          rs_integration:textures/gui/resonance_backpack_void.png
//          my_texture_pack:backpack_skin.png
```

配置项：
```toml
[backpack]
# 纹理命名空间（允许其他 mod 或资源包提供纹理）
texture_namespace = "rs_integration"
# 纹理文件名（不含扩展名），资源包可覆盖同名文件
texture_name = "resonance_backpack"
```

#### 纹理规范

| 属性 | 值 |
|---|---|
| 尺寸 | 256×256（足够容纳 176×166 的 GUI 区域） |
| 格式 | PNG, RGBA |
| 槽位背景 | 18×18 像素每个槽，间隔 2px |
| 背包"布料"区域 | 槽位区域外侧，模拟背包织物/皮革质感 |
| 透明区域 | 背包轮廓外侧为透明 |

**建议的纹理结构**：
```
256×256 画布:
  ┌──────────────────────────────┐
  │  (透明边距 ~40px)            │
  │    ┌──────────────────┐      │
  │    │  背包布料纹理      │      │
  │    │  ┌──┐ ┌──┐ ┌──┐  │      │  ← 9 列
  │    │  └──┘ └──┘ └──┘  │      │
  │    │  ...共 6 行...    │      │
  │    │  └──┘ └──┘ └──┘  │      │
  │    │  扣环/装饰        │      │
  │    └──────────────────┘      │
  └──────────────────────────────┘
```

### 物品模型（3D 掉落物/手中）

共振盘作为物品在世界中显示时，使用 32×32 的像素纹理（`resonance_storage_disk.png`），由标准 `minecraft:item/generated` 模型加载。资源包可以通过覆盖此纹理改变物品外观：

```
资源包覆盖:
  <pack>/assets/rs_integration/textures/item/resonance_storage_disk.png
```

### 实施路线（UI 部分）

| 步骤 | 内容 |
|---|---|
| 1 | `ResonanceBackpackContainer` — 基于 `IStorageDisk` 的容器 |
| 2 | `ResonanceBackpackScreen` — 背包 GUI 渲染 |
| 3 | 默认背包纹理绘制（256×256 像素画） |
| 4 | 右键磁盘驱动器 → 打开背包 |
| 5 | 快捷键 + RS Grid 按钮 |
| 6 | 配置系统（纹理路径切换） |
| 7 | 被动效果状态栏集成 |

---

## 性能

| 指标 | v1（全 RS 扫描） | v2（共振盘） |
|---|---|---|
| 扫描范围 | 500-2000 物品类型 | 共振盘内 10-50 物品类型 |
| AttributeScanner | ~0.01ms/tick | ~0.0001ms/tick |
| TickSimulator | ~0.005ms/tick | ~0.0005ms/tick |
| 非被动物品污染风险 | 无（本来就是全量） | 无（insert 硬性拒绝） |

**每 20 tick 扫描 50 个物品，占 tick 预算 < 0.001%。**

---

## 实施路线

### Phase 1：共振盘基础设施

| 步骤 | 内容 |
|---|---|
| 1 | `ResonanceDiskWrapper` — 包装 `ItemStorageDisk`，insert 硬性过滤 |
| 2 | 共振盘物品 + 配方（箱子 + 外壳）— 单一等级 |
| 3 | 共振盘放入磁盘驱动器时自动注册为 wrapper |
| 4 | `PassiveRegistry` — 三来源合并的被动物品列表 |
| 5 | 编译验证 + 测试 |

**产出**：共振盘存在于 RS 中，只接受被动物品。

### Phase 2：属性扫描 + Tick 模拟

| 步骤 | 内容 |
|---|---|
| 6 | `PassiveEffectEngine` — 串联调度，共振盘发现 |
| 7 | `AttributeScanner` — 遍历共振盘，增量更新 transient modifier |
| 8 | `TickSimulator` — 白名单 + direct disk extract/insert |
| 9 | JSON 白名单配置 + 常用物品适配 |
| 10 | Config + i18n |

**产出**：共振盘内的被动物品效果全部激活。

### Phase 3：背包遍历 Mixin 重定向

| 步骤 | 内容 |
|---|---|
| 11 | `RSInventoryBridge` — 共享工具类，合并背包 + 共振盘 |
| 12 | Avarice Ring Mixin — 替换背包遍历 → 共振盘 + 背包 |
| 13 | Nine Sword Book Mixin — NBT 走 `mutates=true` 路径 |
| 14 | Pyromancer Staff Mixin — 消耗重定向到共振盘 |
| 15 | 后续按需添加 |

### Phase 4：共享背包 UI

| 步骤 | 内容 |
|---|---|
| 16 | `ResonanceBackpackContainer` — 基于 `IStorageDisk` 的容器，直接从共振盘读写 |
| 17 | `ResonanceBackpackScreen` — 背包 GUI，渲染背包纹理 + 槽位 |
| 18 | 默认背包背景纹理（256×256 像素画，`textures/gui/resonance_backpack.png`） |
| 19 | 右键磁盘驱动器打开背包 + 快捷键 + Grid 按钮 |
| 20 | 纹理路径配置项（`backpack_texture_namespace/name`），资源包可直接覆盖 |
| 21 | 被动效果列表 + 容量状态栏集成在背包 GUI 底部 |

### Phase 5：深水区验证

| 步骤 | 内容 |
|---|---|
| 19 | 缓存同步测试（mutates=true 物品 tick 后 Grid 刷新） |
| 20 | 路由优先级测试（普通物品是否被拒绝） |
| 21 | NBT 变体测试（不同耐久/杀敌数的同种物品共存） |

---

## 左侧边栏自定义按钮

### RS 原版渲染管线

RS 左侧边栏按钮（`SideButton`）的渲染方式为**纹理图集叠加**，而非 ItemStack 渲染：

1. **18×18 帧背景** — 从 `textures/icons.png` 的 UV(238, 16) blit，1px DGRAY(54) 外框 + MGRAY(139) 左上内侧高光 + #626262 暗色图标底 + #494949 右下内侧阴影 → 经典"内凹图标区"斜角
2. **16×16 图标** — 各子类覆盖 `renderButtonIcon()`，从 `icons.png` 其他区域 blit
3. **悬停层** — 鼠标悬停时从 UV(238, 54) blit 18×18 半透明叠加

按钮尺寸 **18×18**，排列于 GUI 左侧 `leftPos - 20`，垂直堆叠，间距 2px。

### 按钮排布

有两个自定义按钮，插在 RS 原生 5 个 SideButton 下方、机器标签页上方：

```
leftPos - 20
    │
    ▼
┌──────────────────────┐
│ RS SideButton 1      │  视图类型
│ RS SideButton 2      │  排序方向
│ RS SideButton 3      │  排序模式       ← RS 原生，始终存在
│ RS SideButton 4      │  搜索模式
│ RS SideButton 5      │  网格大小
├──────────────────────┤
│ Machine Center       │  机器管理中心入口  ← 替换旧标签页（ENABLE_MACHINE_GUI_TABS）
│                      │    点击 → 弹出 MachineHub 浮层
│ Resonance Backpack   │  共振背包入口      ← 新增（网络中有共振盘时）
└──────────────────────┘
```

**间距规则**：
- RS 原生按钮之间：2px（RS 自带）
- 自定义区域与 RS 最后一个按钮之间：4px 分隔
- Machine Center 与 Resonance Backpack 之间：2px

### 可见性条件

| 按钮 | 可见条件 |
|------|---------|
| Machine Center | `ENABLE_MACHINE_GUI_TABS` 为 true | 替换旧机器标签页，点击打开 MachineHub 浮层 |
| Resonance Backpack | 玩家的 RS 网络中至少有一个共振盘插入磁盘驱动器 | 新增 |
| Machine Hub/Tabs | `ENABLE_MACHINE_GUI_TABS` 为 true，且至少有一台机器绑定 |

**共振背包按钮**的可见性需要网络查询：客户端需知道当前网络是否包含共振盘。实现方式：
- 客户端在打开 GridScreen 时发送一次查询包
- 服务端响应：检查 `ItemStorageCache.getStorages()` 中是否有 `ResonanceDiskWrapper` 实例
- 缓存结果，屏幕关闭时清除

### 无线访问器兼容

RS 的**次元访问器**（`WirelessGridItem`）和物理 Grid 方块使用**同一个** `GridScreen` 类。RS JAR 中不存在独立的 `WirelessGridScreen`，无线访问器右键后打开的也是 `com.refinedmods.refinedstorage.screen.grid.GridScreen`。

这意味着所有通过 Mixin 注入到 `GridScreen` 的自定义按钮（包括 Machine Center、Resonance Backpack、Machine Hub/Tabs）**在无线访问器打开的 Grid 界面中自动可见**，无需额外适配。

唯一的差异是网络解析路径：
- 物理 Grid：通过 Grid 方块坐标 → `INetworkNode` → `INetwork`
- 无线访问器：通过 `NetworkItem` NBT（controller 坐标）→ 查找 controller → `INetwork`

`RSIntegrationNetwork.resolveFromPlayerInventory()` 已处理两种路径，按钮的网络查询逻辑直接复用即可。

### 自制按钮纹理

遵循 RS 的 SideButton 渲染方式，自制纹理（帧 + 图标分离，同一文件内合成）：

| 文件 | 尺寸 | 用途 |
|---|---|---|
| `textures/gui/machine_center_sidebutton.png` | 18×18 | 机器中心普通态：RS 同款帧 + 信号条图标 |
| `textures/gui/machine_center_sidebutton_hover.png` | 18×18 | 机器中心悬停态：亮色边框 + 同图标 |
| `textures/gui/resonance_backpack_sidebutton.png` | 18×18 | 共振背包普通态：RS 同款帧 + 共振盘图标（14×14 居中） |
| `textures/gui/resonance_backpack_sidebutton_hover.png` | 18×18 | 共振背包悬停态：亮色边框 + 同图标 |

帧像素结构完全复刻 RS `icons.png`（1px DGRAY 外框 + MGRAY 左上高光 + #626262 暗底 + #494949 右下阴影）。两个按钮的帧**像素级一致**，仅图标不同。

**图标设计**：
- 机器中心：信号条（3 条水平进度仪表，逆时针旋转 90°）— 简洁几何，暗色金属，单点琥珀色。寓意指标、状态、性能监控。
- 共振背包：共振盘物品纹理（32×32 → 14×14 LANCZOS，1px 透明内边距防渗透）— 紫色核心 + 青色共振环。直接用物品纹理保持视觉一致性。

脚本：`scripts/gen_machine_sidebutton.py`、`scripts/gen_resonance_sidebutton.py`

### 渲染调用

每个自定义按钮渲染时：
1. blit 18×18 帧（含内嵌图标）— 根据 hover 状态选择普通/悬停纹理
2. 悬停时叠加半透明高亮层
3. 悬停时渲染 tooltip

```java
// 单个自定义按钮的渲染
ResourceLocation tex = isHovered ? HOVER_TEXTURE : NORMAL_TEXTURE;
gfx.blit(tex, x, y, 0, 0, 18, 18, 18, 18);
if (isHovered) {
    RenderSystem.setShaderColor(1, 1, 1, 0.5f);
    gfx.blit(HOVER_OVERLAY, x, y, 0, 0, 18, 18, 18, 18);
}
```

> **注意**：与 RS 的 `icons.png`（集中式图集，所有按钮共享一个文件）不同，我们采用独立纹理文件。这避免了修改 RS JAR 的纹理，且更易于资源包单独覆盖每个按钮。

---
## 开放决策

- [x] **核心模型反转**：RS = 仓库 inert，共振盘 = 激活区。已决策。
- [x] **共振盘 insert 硬性过滤**：只接受被动物品。已决策。
- [x] **mutates=true 直接磁盘级操作**：disk.extract → tick → disk.insert。已决策。
- [x] **UI 形态**：共享背包 GUI。`ResonanceBackpackContainer` + `ResonanceBackpackScreen`，传统 RPG 背包界面而非 RS Grid 标签页。已决策。
- [x] **实现路线**：Hybrid 混合模式。通过 RS SPI 注册 `IStorageDiskFactory`（`factoryId: "rsi:resonance"`），工厂内部返回 Wrapper 代理真实 `ItemStorageDisk`，避免重写序列化与 NBT 合并。已决策。
- [x] **每网络一个共振盘**：一个 RS 网络只允许一个共振盘。插入第二个时拒绝并提示。已决策。
- [x] **共振盘容量等级**：单一等级，容量与槽位与玩家背包一致（36 槽位 / 4×9）。已决策。
- [x] **共振盘配方**：箱子 + 外壳。已决策。
- [x] **背包纹理换肤**：通过 ResourceLocation 加载背景纹理，资源包在 `assets/rs_integration/textures/gui/` 下覆盖同名文件即可。进阶支持配置项切换纹理 namespace/name。已决策。
- [ ] **PassiveRegistry 更新时机**：属性扫描结果变化时自动更新？还是需要配置重载命令？
- [x] **快捷栏模式**：Phase 1-2 不做热键栏模式。共振盘中无格子概念。
- [x] **属性 count 叠加**：强制无视 count，每种物品仅按 1 个生效。
- [x] **Capability 级 Mixin**：不做。主动消耗物品的 Mixin 注入具体业务方法。
