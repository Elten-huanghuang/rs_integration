# Refined Storage long 级三合一存储架构评估报告

> 状态日期：2026-09-08  
> 工作树：`D:\sd\rs-integration`  
> 当前基线：Minecraft 1.20.1、Forge 47.3.22、Refined Storage 1.12.4  
> 报告性质：反编译调研与实施方案，不表示功能已经实现。

## 1. 目标

在当前 Refined Storage（下文简称 RS）网络中提供一张三合一存储盘，使它能够同时保存并调度：

- 固体：Minecraft `ItemStack`；
- 液体：Forge `FluidStack`；
- 气体/化学品：Mekanism Chemical，设计上覆盖 Gas、Infusion、Pigment、Slurry 等 1.20.1 Chemical 分类。

数量要求为真实的正 `long` 范围，即单项及每类总量能够达到 `Long.MAX_VALUE`，而不是只在提示中显示一个大数字。配套终端必须使用 `long` 保存、同步、排序和显示数量，并能对三类资源执行安全的插入、提取和长任务调度。

## 2. 最终结论

该目标可以实现，但不能只注册一个新的 `IStorageDisk`，也不能依靠少量 Mixin 把 RS 原有终端改成 `long`。

推荐方案是：

1. 一张磁盘物品只保存 UUID 和摘要；
2. 世界 `SavedData` 按 UUID 保存三类资源的真实 `long` 库存；
3. 新增连接 RS 网络的三合一磁盘舱；
4. 向 RS 暴露降级的 Item/Fluid 兼容视图；
5. 为 Mekanism Chemical 建立独立缓存、输入输出和外部存储接口；
6. 新增统一终端，通过自有协议准确同步和展示三类 `long` 数量；
7. 长任务调度器把物品和流体操作拆成 RS 能接受的 `int` 批次执行。

不推荐直接修改 RS 全链路的 `int` 类型。Java 方法签名不能通过普通 Mixin 稳定地从 `int` 改成 `long`；若强行实施，实际等同于维护一个 RS 分支，并会破坏大量附属模组兼容。

## 3. 调研对象和版本边界

本次检查了以下本地 JAR：

| JAR | 平台 | 可借鉴内容 | 能否直接使用 |
|---|---|---|---:|
| `ae2lt-2.1.0-beta.2.jar` | MC 1.21.1 NeoForge、AE2 19.2 | 固定无限盘、特定资源 long 盘、高低位容量 | 否 |
| `ae2omnicells-1.21.1-neoforge-1.1.6.jar` | MC 1.21.1 NeoForge、AE2 19.2 | 通用 Key、BigInteger、UUID 分盘存档、Chemical Key | 否 |
| `extendedae_plus-1.6.2.jar` | MC 1.21.1 NeoForge、ExtendedAE | BigInteger 无限盘、UUID + SavedData、饱和公开数量 | 否 |
| `refinedstorage-1.12.4.jar` | MC 1.20.1 Forge | 当前目标 API 和所有硬限制 | 是 |

三个 AE2 扩展都针对 1.21.1 NeoForge，不能将类文件或注册代码直接移入当前 1.20.1 Forge 工程。可借鉴的是数据结构、存档所有权和网络降级策略。

## 4. 三个参考模组的实现方式

### 4.1 AE2 Omni Cells

普通通用盘的核心是 `Object2LongMap<AEKey>`，BigInteger 盘则使用 `Object2ObjectMap<AEKey, BigInteger>`。同一个 `AEKey` 抽象能够承载物品、流体和第三方资源类型。

BigInteger 盘的重要行为：

- 每张盘通过 UUID 定位世界存档；
- 每项资源保存 `AEKey` 和 BigInteger 数量；
- BigInteger 使用字节数组序列化；
- 对 AE2 `KeyCounter` 公布数量时钳制到 `Long.MAX_VALUE`；
- 插入和提取接口仍按 AE2 的 `long` 请求逐次工作；
- 使用 `AEKeyType.getAmountPerByte()`及分桶总量计算容量占用；
- 盘物品只保存 UUID、使用量、类型数、状态和少量 Tooltip 样本。

Chemical 支持并不是 Omni Cells 自己创建了 Chemical 网络。它识别 MekAE2 提供的 `MekanismKey`，再利用 AE2 已有的通用 `AEKey` 管线。RS 1.12.4 没有等价的通用资源键，因此不能照搬这一层。

### 4.2 ExtendedAE Plus

`InfinityBigIntegerCellInventory` 使用 `Map<AEKey, BigInteger>`，`InfinityStorageManager` 使用 `Map<UUID, InfinityDataStorage>`集中管理存档。

它的关键策略是：

- 真实库存可以超过 `long`；
- 对 AE2 网络公开时最多报告 `Long.MAX_VALUE`；
- 精确总量只在自己的 Tooltip 中格式化显示；
- 数据与物品 NBT 分离，盘只承担身份路由；
- 插入、提取、分区过滤和升级卡仍在存储视图中执行。

这是本方案“服务端精确数据 + 外部协议饱和视图”的主要参考。

### 4.3 AE2 Lightning Tech

该模组至少展示了三种不同语义：

- 固定无限盘：只允许一个预定 `AEKey`，不维护普通库存，匹配时直接接受插入或返回提取量；
- Bulk Lightning 盘：只保存两个固定资源，每种数量直接使用 NBT `long`；
- 通用大容量盘：磁盘物品保存 `capacityLo/capacityHi`，容量追踪实现由 Thunderbolt Core 提供。

固定无限盘为了终端和调用稳定性，公开数量使用约 `Integer.MAX_VALUE * amountPerUnit`，并非真实通用仓库。它适合创造模式资源源或少量固定资源，不适合作为本项目三合一存储的主体。

## 5. RS 1.12.4 的硬限制

### 5.1 数量全链路为 int

RS 1.12.4 的以下接口和数据结构都使用 `int`：

- `IStorage.insert`、`IStorage.extract`；
- `IStorage.getStored`；
- `IStorageDisk.getCapacity`；
- `IStorageCache.add/remove`；
- `IStackList.getCount`；
- `StackListResult.change`；
- `ItemGridStack`、`FluidGridStack` 的数量；
- Grid 完整更新和增量更新包；
- 磁盘容量同步数据和响应包；
- RS 自动合成请求数量。

因此，即使自定义磁盘内部使用 `long`，原版 RS 缓存和原版终端也只能得到一个 `int` 兼容视图。

### 5.2 只有两种资源类型

`StorageType` 只有 `ITEM` 和 `FLUID`。RS 没有 Chemical 类型、Chemical 缓存、Chemical Grid Handler 或 Chemical 自动合成请求。

Mekanism Chemical 不能伪装成 `FluidStack`：两者的注册表、能力、容器、过滤、配方和传输语义均不同。这样做会造成错误合并、无法连接 Chemical 管道以及存档不兼容。

### 5.3 一张原版磁盘只能选择一种视图

`IStorageDiskProvider.getType()`只返回一个 `StorageType`。`StackUtils.createStorages` 根据它把磁盘放入磁盘驱动器的 Item 数组或 Fluid 数组，不会同时放入两个数组。

同一个 Java 对象也不能同时以不同泛型重复实现 `IStorageDisk<ItemStack>` 和 `IStorageDisk<FluidStack>`。三合一数据必须由一个根记录拥有，再由三个独立适配器访问。

## 6. 推荐总体架构

```text
三合一磁盘物品
       |
      UUID
       |
UnifiedDiskSavedData
  `- UUID -> UnifiedDiskRecord
       |- Map<ItemKey, long>
       |- Map<FluidKey, long>
       `- Map<ChemicalKey, long>
                  |
        三合一磁盘舱（RS 网络节点）
           |- RS Item 兼容适配器
           |- RS Fluid 兼容适配器
           `- Chemical 精确存储视图
                  |
              统一终端
       Item + Fluid + Chemical + long
```

### 6.1 为什么推荐专用磁盘舱

专用磁盘舱可以实现 `INetworkNode` 和 `IStorageProvider`，向 RS 原生缓存提供 Item/Fluid 视图，同时向本模组的 Chemical 服务注册 Chemical 视图。

与第一版直接修改原版磁盘驱动器相比，它有以下优势：

- 不依赖 `StackUtils.createStorages` 的私有实现细节；
- 一个槽位能够明确激活三个适配器；
- 能使用真实 long 比例显示磁盘状态；
- Chemical 生命周期能与 RS 网络连接状态同步；
- 更容易实现 UUID 冲突检测和每槽 revision；
- RS 升级时需要适配的 Mixin 更少。

后续若必须让三合一盘进入原版 RS 磁盘驱动器，可以增加可选兼容层，在创建存储数组时为同一 UUID 建立 Item/Fluid 两个视图，并在驱动器节点上注册 Chemical 视图。它应作为第二阶段兼容功能，而不是数据核心。

## 7. 数据模型

### 7.1 根记录

每张盘对应一个 `UnifiedDiskRecord`，至少包含：

| 字段 | 类型 | 用途 |
|---|---|---|
| `diskUuid` | UUID | 稳定身份 |
| `owner` | UUID/空 | 所有者与安全校验 |
| `schemaVersion` | int | 数据迁移 |
| `revision` | long | 客户端增量同步和缓存失效 |
| `itemStored` | long | 物品总量 |
| `fluidStored` | long | 流体总量，单位 mB |
| `chemicalStored` | long | Chemical 总量 |
| 三类容量 | long | 各自的最大容量 |
| 三类资源映射 | Key -> long | 精确库存 |

数量有效范围定义为 `0..Long.MAX_VALUE`。负数、零数量条目、未知类型和不完整 Key 均不得进入活动库存。

### 7.2 资源身份

- Item Key：物品注册 ID + 完整 NBT；
- Fluid Key：流体注册 ID + FluidStack NBT；
- Chemical Key：Chemical 分类 + Chemical 注册 ID。

物品的数量不能存进显示用 `ItemStack.getCount()`；服务端和客户端都应使用数量为 1 的模板，加独立 `long amount`。流体同理，不能依赖 `FluidStack.getAmount()`承载 long 总量。

### 7.3 容量语义

推荐一张物理磁盘包含三个独立 long 配额：

- 物品容量按“个”计算；
- 流体容量按 mB 计算；
- Chemical 容量按 Mekanism 原生数量单位计算。

这仍然是一张三合一盘，但避免了“一个物品等于多少 mB 流体”的任意折算，也保持 RS 用户熟悉的容量语义。

如果产品必须使用一个共享容量池，应仿照 AE2 按资源类型定义 `amountPerStorageUnit`，并使用分桶总量和向上取整计算占用。该模式需要额外处理不足一单位、不同资源类型的余数和规则变更后的迁移，不建议作为第一版。

## 8. long 安全规则

所有插入、提取、统计和调度必须遵守以下约束：

- 判断剩余空间时使用 `requested <= capacity - stored`；
- 不使用可能先溢出的 `stored + requested <= capacity`；
- 总量更新使用显式饱和或精确失败，不允许自然溢出；
- 提取量为 `min(requested, existing)`；
- 资源归零后移除映射条目；
- 排序使用 `Long.compare`，不能用强制转 int 或相减比较；
- 服务端拒绝负数、零、超过协议上限和类型不匹配的客户端请求；
- 每次真实变更只递增一次 revision，模拟操作不得修改 revision；
- 读取旧存档后重新计算摘要，不能无条件相信已保存的总量字段。

## 9. 存档、UUID 和防复制

完整库存不应写进磁盘物品 NBT。大量 NBT 会增加区块、背包、网络包和玩家数据的体积，也容易因物品复制产生库存复制。

推荐保存方式：

- 盘物品：UUID、格式版本、客户端摘要；
- 世界 `SavedData`：UUID 对应的完整三类库存；
- 同 UUID 的物品访问同一份数据，不复制库存；
- 同 UUID 同时插入多个磁盘舱时，默认只允许一个激活，或者明确实现共享访问锁；
- 拔出或销毁盘不会立即删除后端记录；
- 孤立记录只通过管理员命令或带保留期的回收机制清理；
- 数据记录保存未知注册项的隔离区，模组暂时缺失时不能丢弃整张盘；
- 存档格式必须带 schemaVersion，并提供逐版本迁移；
- 备份恢复后 UUID 行为必须确定，不能根据物品位置重新生成身份。

## 10. RS Item/Fluid 兼容层

三合一磁盘舱向 RS 暴露两个独立适配器：

- Item 适配器实现 `IStorage<ItemStack>`或对应磁盘视图；
- Fluid 适配器实现 `IStorage<FluidStack>`或对应磁盘视图；
- 两者共享同一个 `UnifiedDiskRecord`，但只访问各自资源映射；
- RS 的单次 `int` 请求转换为内部 long 更新；
- 原生 importer、exporter、storage bus 可继续分批工作。

向 RS 公布的单项数量和总容量最多只能是 `Integer.MAX_VALUE`。这只是兼容视图，不是真实数据。

必须额外防止 RS 缓存溢出：当普通 RS 磁盘和三合一盘同时包含同一种资源时，`IStackList` 仍可能把两个正 int 相加为负数。兼容实现需要对 ItemStackList/FluidStackList 的聚合加减实施饱和保护，或者让自定义节点维护经过限制的公开总量。

当真实数量从 30 亿降到 29 亿时，公开数量仍为 `Integer.MAX_VALUE`，不应发送负 delta；当真实数量跨过 int 边界时，应重新计算公开视图或完整失效对应 RS 缓存。

原版 RS 终端只能显示这个降级数量。精确 long 数量必须由统一终端读取自有快照。

## 11. Chemical 存储与网络

### 11.1 资源范围

目标若称为 Mekanism Chemical，建议从数据格式开始支持 1.20.1 的完整 Chemical 分类，而不是只保存 Gas。界面可以先只开放 Gas，但存档 Key 必须包含分类，避免以后迁移时发生注册 ID 冲突。

### 11.2 Chemical 服务

新增独立的 Chemical 存储契约，至少支持：

- 获取精确快照；
- 模拟插入和执行插入；
- 模拟提取和执行提取；
- 优先级、访问模式和过滤；
- revision 变化监听；
- 网络连接和断开；
- 所有者与 RS 安全权限复用。

Chemical 缓存以 RS 网络实例为边界，聚合该网络图中所有活动三合一磁盘舱和 Chemical 外部存储。缓存只保存 Key 与 long 数量，不保存可变的 ChemicalStack 引用。

### 11.3 输入输出设备

为了让 Chemical 不只“能在终端显示”，还需要提供：

- Chemical Importer：从相邻 Mekanism handler 导入；
- Chemical Exporter：按过滤器向相邻 handler 输出；
- Chemical External Storage：把外部化学储罐映射到统一网络；
- 终端容器交互：支持相应 Chemical 容器；
- 每 tick 操作次数和总传输量预算；
- 断网、目标满、能力失效和区块卸载后的安全恢复。

## 12. 统一终端

统一终端使用自己的菜单、屏幕、缓存监听器和网络包，不复用 RS Grid 的数量协议。

每个终端条目至少包含：

- 稳定 entry ID；
- 资源类别：Item、Fluid、Chemical；
- 资源身份和数量为 1 的渲染模板；
- 精确 `long amount`；
- 是否可合成、最后修改者等可选信息；
- 服务端 revision。

终端行为：

- 默认在一个网格中混合显示三类资源；
- 提供“全部、物品、流体、Chemical”分段筛选；
- 一个搜索框同时匹配名称、模组、标签和 Chemical 分类；
- 数量排序使用 long；
- 格子显示紧凑数量，Tooltip 显示完整十进制整数；
- `Long.MAX_VALUE` 必须显示为 `9,223,372,036,854,775,807`，不能变成负数；
- 物品提取仍以背包可容纳的实际堆叠大小为限；
- 流体和 Chemical 容器交互按容器能力决定单次传输量；
- 所有操作由服务端重新验证网络、距离、权限、Key 和请求量。

### 12.1 同步协议

首次打开终端不能把整个库存塞进一个无限增长的数据包。推荐：

1. 服务端生成某 revision 的不可变快照；
2. 发送快照头和总页数；
3. 分页发送资源描述与 long 数量；
4. 客户端完整收齐后原子替换视图；
5. 后续只发送带 revision 的 set/remove 增量；
6. 客户端发现 revision 缺失、乱序或页面超时后重新请求快照。

分页限制按条目数和编码后字节数双重控制。客户端只缓存显示所需的数据，不应直接访问服务端 SavedData。

## 13. 调度模型

### 13.1 普通输入输出

物品和流体继续允许 RS 原生机器调用兼容适配器。虽然一次操作只有 int 数量，但重复调用可以使真实库存达到 long。

Chemical 由专用输入输出设备调用 long 存储接口。即使底层 Chemical handler 支持较大请求，也应受每 tick 预算限制，避免单个任务长期占用服务器线程。

### 13.2 原子传输规则

每次传输遵循：

1. 模拟源可提取量；
2. 模拟目标可接收量；
3. 取两者及本 tick 预算的最小值；
4. 执行源提取；
5. 执行目标插入；
6. 目标少收时立即回滚源，回滚失败则写入持久恢复队列。

禁止先修改库存，再假设外部目标一定接收成功。模拟和执行之间外部状态可能变化，因此必须处理部分接收。

### 13.3 long 长任务

长任务记录至少包含：

- 任务 UUID；
- 资源 Key；
- requested、completed 两个 long；
- 源、目标和绑定网络；
- 状态、失败原因和 revision；
- 每 tick 最大操作量。

物品和流体任务按不超过 `Integer.MAX_VALUE` 的块调用 RS，实际块大小还应受容器和 tick 预算限制。任务需要支持暂停、取消、玩家离线、区块卸载、网络断开和服务器重启恢复。

## 14. 自动合成边界

如果“调度”只指库存输入、输出、终端取放和机器传输，上述架构已经覆盖。

如果还要求一次申请 long 数量的自动合成，则必须新增 `LongCraftOrder` 协调层：

- 将一个 long 请求拆分为多个 RS int crafting task；
- 只在前一批被接受或完成后继续提交，避免瞬间创建大量任务；
- 汇总每批缺料、取消、产出和退款；
- 重启后从已完成批次数继续；
- 对外仍展示一个 long 总任务。

RS 1.12.4 不支持 Chemical 自动合成。Chemical Pattern、Mekanism 机器提供者、输入预留、产物确认和失败退款需要另建一套适配器。这是独立于磁盘和终端的第二阶段工程，不能因 Chemical 已经可存储就宣称自动合成完成。

## 15. 性能设计

大数量本身不会显著增加内存；真正决定成本的是不同资源 Key 的数量。因此需要：

- 使用按资源类型分区的哈希映射；
- 缓存三类总量和公开饱和数量；
- 变更一个 Key 时只更新对应缓存和 revision；
- 终端使用分页快照和增量同步；
- 搜索索引在服务端或客户端按稳定模板构建，不反复解析完整 NBT；
- 限制单盘最大不同类型数，或至少提供配置和诊断；
- 存档只在脏数据时写入，并避免每次小变更重写所有磁盘记录；
- Chemical 外部存储扫描采用事件失效或低频轮询，不逐 tick 全量遍历。

如果单张盘允许数十万种 NBT 变体，即使数量都是 1，也会成为存档、网络和终端性能问题。long 容量不能替代类型数量治理。

## 16. 安全与一致性

- 统一终端复用 RS 网络是否运行、所有者和安全权限；
- 客户端请求只表达意图，不携带可信库存结果；
- 服务端以稳定 Key 再次解析条目，拒绝失效 entry ID；
- SIMULATE 不得触发存档、revision 或客户端 delta；
- PERFORM 成功后，数据、缓存和存档脏标记按固定顺序更新；
- 多个视图访问同一 UUID 时必须在服务器线程串行执行；
- 后台搜索和排序只能使用不可变快照，不访问活存储映射；
- 未安装 Mekanism 时不得提前链接 Mekanism 类，Chemical 集成必须放在可选加载边界；
- 未知 Chemical 或 Fluid 注册项进入隔离数据，不能自动改成空气或空资源；
- 管理员修改、迁移和恢复操作必须记录磁盘 UUID 与前后 revision。

## 17. 不推荐方案

### 17.1 把 RS 全部 int 改成 long

需要同时修改接口、实现、缓存、列表、Grid、菜单、客户端对象、网络包、磁盘同步、自动合成和所有调用方。第三方 RS 扩展仍按旧方法描述符编译，运行时会失效。维护成本和兼容风险最高。

### 17.2 只让磁盘内部用 long

这样只能做到“真实库存很大”，不能保证原版 RS 缓存不溢出，也不能让终端准确显示。若没有自定义终端、饱和缓存规则和跨边界失效逻辑，最终会出现负数、幽灵库存或错误 delta。

### 17.3 把 Chemical 伪装成 Fluid

它不能正确连接 Mekanism Chemical capability，也无法区分 Chemical 分类、容器和配方。短期减少类数量，长期会形成不可迁移的错误存档。

### 17.4 直接移植三个 1.21.1 模组的类

目标平台、加载器、Minecraft API、AE2 API 和数据组件系统均不相同；AE2LT 还依赖 Thunderbolt Core。应重新实现适合 RS 1.12.4 的协议边界，不复制其字节码或版本专用实现。

## 18. 实施阶段

### P0：数据核心

- 定义三类稳定 Key；
- 完成 long 安全算术；
- 完成 UUID + SavedData；
- 完成格式版本、未知条目隔离和重启测试；
- 不接 UI，不接外部网络。

### P1：三合一磁盘舱和 RS 兼容

- 注册磁盘物品和专用磁盘舱；
- 完成 Item/Fluid 两个适配器；
- 完成 RS 缓存饱和保护；
- 验证 importer、exporter、storage bus；
- 验证同资源同时存在于普通盘和三合一盘。

### P2：Chemical 网络

- 完成 Chemical Key 和缓存；
- 完成磁盘 Chemical 视图；
- 完成 importer、exporter 和 external storage；
- 完成 Mekanism 缺失时的可选加载测试。

### P3：统一终端

- 完成三类混合列表、筛选、搜索和 long 排序；
- 完成分页快照、revision delta 和重新同步；
- 完成三类资源取放和权限检查；
- 完成长数量显示和完整 Tooltip。

### P4：长任务与自动合成

- 完成持久化 long 传输任务；
- 完成公平调度、限速、取消和恢复；
- 完成 RS int crafting task 分批协调；
- 如产品要求，再实现 Chemical Pattern 和机器适配。

### P5：可选原版磁盘驱动器兼容

- 允许三合一盘插入原版 RS 磁盘驱动器；
- 为同槽建立 Item/Fluid/Chemical 三个视图；
- 验证与当前 `StackUtilsMixin` 及其他磁盘附属的冲突；
- 把该兼容作为可关闭功能，专用磁盘舱始终保留为稳定路径。

## 19. 验收矩阵

### 19.1 数值边界

- 0、1、`Integer.MAX_VALUE - 1`；
- `Integer.MAX_VALUE`、`Integer.MAX_VALUE + 1`；
- `Long.MAX_VALUE - 1`、`Long.MAX_VALUE`；
- 满盘继续插入、空盘继续提取；
- 总量接近上限时新增不同 Key；
- 多个普通 RS 存储与三合一盘聚合同一种资源。

### 19.2 数据与生命周期

- 存档、退出、重启和崩溃恢复；
- 磁盘物品复制但 UUID 相同；
- 两个磁盘舱同时放入同 UUID；
- 盘被销毁、磁盘舱被破坏、网络控制器断电；
- 注册项暂时缺失后重新安装模组；
- schema 升级和备份降级保护。

### 19.3 终端和协议

- 三类资源同时显示、搜索、筛选和排序；
- 精确显示超过 int 的数量；
- 大量类型分页；
- 页面丢失、重复、乱序和过期 revision；
- 玩家在打开终端后失去权限或离开范围；
- 伪造负数、超范围数量和未知 entry ID。

### 19.4 调度

- Item、Fluid、Chemical 的模拟与执行一致；
- 目标部分接收和完全拒绝；
- 外部容器或机器在模拟后状态变化；
- 任务中途断网、卸载区块、玩家离线和服务器重启；
- 多个长任务公平执行；
- 取消任务不丢资源、不重复退款。

### 19.5 安装组合

| 组合 | 预期行为 |
|---|---|
| RS + Mekanism | 完整三合一功能 |
| 仅 RS | Item/Fluid 正常，Chemical 功能隐藏且不崩溃 |
| RS + 其他磁盘附属 | 缓存、优先级和同资源聚合不溢出 |
| 客户端缺少本模组 | 按 Forge 模组握手拒绝连接，不解析自定义包 |

## 20. 发布门槛

只有同时满足以下条件，才能宣称“long 级三合一存储已经完成”：

- SavedData 中三类资源均真实保存 long 数量；
- 重启后数量、Key 和 UUID 完全一致；
- 统一终端准确显示超过 int 的完整数量；
- 原版 RS Item/Fluid 缓存不会因聚合发生负数或回绕；
- Chemical 能通过 Mekanism 能力真实导入和导出；
- 三类资源在目标满、断网和中途失败时不丢失、不复制；
- 长任务可恢复，并且不因一次大请求长期阻塞服务器；
- RS-only 环境不会加载 Chemical 类；
- 已通过专服和真实整合包测试，而不仅是单元测试。

不能只凭以下现象宣称完成：

- Tooltip 显示 long；
- 磁盘 NBT 中存在 long 字段；
- 原版终端显示 `2.1G`；
- Chemical 图标出现在终端；
- 单次插入和提取测试成功；
- 自动测试通过但没有进行重启和外部机器测试。

## 21. 最终建议

本项目应采用“独立 long 数据核心，向 RS 提供兼容视图”的路线，而不是修改 RS 的基础数量类型。

第一版产品边界建议是：专用三合一磁盘舱、精确统一终端、Item/Fluid 原生 RS 自动化兼容、Chemical 专用输入输出、持久化 long 传输任务。原版 RS 磁盘驱动器兼容和 Chemical 自动合成放到后续阶段。

这种设计能够满足同一张盘真实保存固体、液体和 Mekanism Chemical，终端准确展示 long 数量，并让三类资源可靠调度；同时将 RS 1.12.4 的 int 限制隔离在兼容边界内，不把整个工程绑定到一个难以维护的 RS 分支。
