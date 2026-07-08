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
| 接受非被动物品 | 是 | **否 — insert 时硬性拒绝** |
| 被动效果扫描 | 否 | **是 — 只有它的内容被扫描** |
| RS Grid 可见 | 是 | 是 |
| 磁盘操作器支持 | 是 | 是 |

### 硬性过滤 — 防呆设计

共振盘的 `insert()` 硬性拒绝非被动物品：

```java
public ItemStack insert(ItemStack stack, int size, Action action) {
    if (!PassiveRegistry.isPassiveItem(stack)) {
        return stack; // 拒绝：这物品没有被动效果，去普通盘
    }
    return delegate.insert(stack, size, action);
}
```

这解决了"挖矿圆石鸠占鹊巢"的问题。共振盘物理上不可能被非被动物品污染。`PassiveRegistry` 由三来源合并：
1. Phase 1 属性扫描发现的物品（运行时动态）
2. Phase 2 JSON 白名单物品
3. Phase 3 事件 Mixin 覆盖的物品

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
        // 根据容量反查 ItemStorageType → 创建 ResonanceDiskItem 实例
        ItemStorageType type = ItemStorageType.getByCapacity(disk.getCapacity());
        ItemStack stack = new ItemStack(ModItems.RESONANCE_DISK.get(type).get());
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
                                                 └── TickSimulator (每 1t)
                                                      遍历盘内物品 → 查白名单 → inventoryTick
                                                      │
                                                      ├── mutates=false → 快照 tick（只读）
                                                      └── mutates=true  → disk.extract → tick → disk.insert
```

四个组件，总量基本不变，但职责更清晰：

| 组件 | 说明 | 行数估计 |
|---|---|---|
| `ResonanceDiskWrapper` | 包装 `ItemStorageDisk`，insert 硬性过滤 + 透传 | ~40 行 |
| `AttributeScanner` | 遍历共振盘物品，提取属性，增量管理 transient modifier | ~100 行 |
| `TickSimulator` | JSON 白名单 + inventoryTick，直接磁盘级读写 | ~60 行 |
| `PassiveEffectEngine` | 串联调度 + 共振盘发现 + 网络解析 | ~80 行 |
| `PassiveRegistry` | 三来源合并（属性扫描 + JSON + 事件物品） | ~30 行 |

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

## UI：共振标签页

在 RS Grid 右侧添加"共振"标签页，与"物品"/"流体"标签并列：

```
┌──────────────────────────────────────────┐
│ RS Grid                     [物品][共振] │
├──────────────────────────────────────────┤
│ ┌──────┐ ┌──────┐ ┌──────┐             │
│ │ 护符  │ │ 戒指  │ │ 药水  │            │
│ │  ✓   │ │  ✓   │ │      │            │
│ └──────┘ └──────┘ └──────┘             │
│ ┌──────┐                                │
│ │ 法杖  │  灰显 = 在RS但不在共振盘       │
│ │  ✗   │                                │
│ └──────┘                                │
├──────────────────────────────────────────┤
│ 激活效果: 急迫II · 夜视 · 力量I          │
│ 共振盘: 234/1024                        │
└──────────────────────────────────────────┘
```

**功能**：
- 切换到共振视图，只显示共振盘内的物品
- 物品左侧 ✓ = 已在共振盘；✗ = RS 中有但共振盘没有
- 点击物品：在共振盘 → 移回普通盘；不在共振盘 → 移入共振盘
- 底部状态栏：当前激活的被动效果列表 + 共振盘容量

**实现**：Grid 上 Mixin 追加 tab + 自定义 `IGridTab` 实现。利用 `getStorages()` 过滤共振盘来构建视图，利用 `disk.extract/insert` 做迁移操作。

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
| 2 | 共振盘物品 + 配方（普通盘 + 催化剂） |
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

### Phase 4：UI

| 步骤 | 内容 |
|---|---|
| 16 | Grid Mixin — 共振标签页 |
| 17 | 物品迁移（共振盘 ↔ 普通盘）交互 |
| 18 | 效果列表 + 容量状态栏 |

### Phase 5：深水区验证

| 步骤 | 内容 |
|---|---|
| 19 | 缓存同步测试（mutates=true 物品 tick 后 Grid 刷新） |
| 20 | 路由优先级测试（普通物品是否被拒绝） |
| 21 | NBT 变体测试（不同耐久/杀敌数的同种物品共存） |

---

## 开放决策

- [x] **核心模型反转**：RS = 仓库 inert，共振盘 = 激活区。已决策。
- [x] **共振盘 insert 硬性过滤**：只接受被动物品。已决策。
- [x] **mutates=true 直接磁盘级操作**：disk.extract → tick → disk.insert。已决策。
- [x] **UI 形态**：Phase 4 Grid 标签页。Phase 1-3 先用命令/日志验证。
- [x] **实现路线**：Hybrid 混合模式。通过 RS SPI 注册 `IStorageDiskFactory`（`factoryId: "rsi:resonance"`），工厂内部返回 Wrapper 代理真实 `ItemStorageDisk`，避免重写序列化与 NBT 合并。已决策。
- [x] **每网络一个共振盘**：一个 RS 网络只允许一个共振盘。已决策。
- [ ] **共振盘容量等级**：1k/4k/16k/64k/创造？用户研究中。
- [ ] **共振盘催化剂配方**：用什么物品作为"催化剂"？（如 RS 的 `ProcessorBinding` 类似物）
- [ ] **PassiveRegistry 更新时机**：属性扫描结果变化时自动更新？还是需要配置重载命令？
- [x] **快捷栏模式**：Phase 1-2 不做热键栏模式。共振盘中无格子概念。
- [x] **属性 count 叠加**：强制无视 count，每种物品仅按 1 个生效。
- [x] **Capability 级 Mixin**：不做。主动消耗物品的 Mixin 注入具体业务方法。
