# 工业级模组机器集成自查清单

每写完一个新的 `XXXBatchDelegate`、`XXXRSModule`、Mixin 或网络包，逐项核对。

---

## 一、事件总线注册 (Event Bus Wiring)

**这是新手向进阶过渡时必交的学费。** Forge 的 `@SubscribeEvent` 注解**不会自动生效**——如果没有注册，监听器就是死代码。

- [ ] **静态订阅者已注册：** 使用 `@Mod.EventBusSubscriber` 注解，或在主类中显式调用 `MinecraftForge.EVENT_BUS.register(YourClass.class)`。
- [ ] **实例订阅者已注册：** 单例类用 `MinecraftForge.EVENT_BUS.register(getInstance())`，客户端 lambda 用 `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () -> MinecraftForge.EVENT_BUS.register(...))`。
- [ ] **客户端隔离：** 客户端专属事件（`ClientPlayerNetworkEvent.LoggingOut`、`ScreenEvent` 等）必须只在物理客户端注册，否则服务端会崩。
- [ ] **注销对称：** 注册了 `register(instance)` 的地方，是否有对应的 `unregister(instance)`？（玩家下线、配置热重载时）

**自查方法：** `grep -r "@SubscribeEvent" src/` 找到所有带注解的类，逐一确认它们在事件总线上注册过。

---

## 二、跨域与网络通信安全 (Network & Security)

- [ ] **主线程同步 (Thread Safety):** 所有的自定义网络包（Packet/Message）处理逻辑，是否已经严格使用 `context.enqueueWork(() -> { ... })` 包裹？（绝对禁止在 Netty 线程直接读写世界或实体）。
- [ ] **坐标防篡改 (Spoofing Defense):** 服务端收到客户端发来的机器操作请求（坐标 X,Y,Z）时，是否进行了严格的 `AltarBindingRegistry.isBound(key, pos, player)` 校验？（防黑客全服隔空偷窃）。
- [ ] **维度解析稳健：** 客户端传来的维度 ID 是否经过 `ResourceLocation.tryParse()` 和 `server.getLevel()` 校验？空维度、无效维度不应导致 NPE。
- [ ] **拒绝同步强载 (Async Chunk Checking):** 在做环境探测（比如 `isBindingFresh` 或检测多方块结构）时，是否使用了 `level.isLoaded(pos)` 乐观跳过？（绝对禁止对未加载的区块调用 `level.getChunk(pos)` 或 `ChunkUtils.loadChunk`，防 TPS 冰封）。
- [ ] **DDOS 限流 (Rate Limiting):** 客户端可频繁触发的网络包（如合成预览、配方查询），是否在服务端加了基于玩家 UUID 的漏桶/令牌桶限流？（防连点器撑爆网络通道）。

---

## 三、界面渲染与客户端欺骗 (GUI & Rendering)

- [ ] **幽灵进度防御 (Null BlockEntity Guard):** 目标机器的 Menu 类中，所有直接读取 `this.blockEntity` 的方法（如 `getCookingProgress`），是否已使用 Mixin `@Inject(cancellable = true)` 做好 Null 检查并返回安全默认值 `0` 或 `0.0F`？
- [ ] **客户端脱战防御 (StillValid Bypass):** 目标机器的客户端屏幕心跳（`containerTick`）或原版容器（`stillValid`）距离检测，是否已在客户端无条件返回 `true`？（防界面闪一下就关）。
- [ ] **NBT 图标补全 (Icon NBT Injection):** 在绑定目标机器提取展示图标时，是否已将该机器的 `BlockEntityTag`（并清洗掉内部巨型 Inventory 数据以防发包溢出）塞入 `ItemStack`，以防 TACZ 等 NBT 驱动模组渲染成紫黑块？
- [ ] **UI 导航栈健壮：** 远程打开机器 GUI → 关闭时返回 RS 终端的导航栈，是否基于 `Minecraft.setScreen(null)` 拦截而非 `Screen.removed()` 回调？（`Screen.removed()` 在子菜单替换时也会触发，导致误判。）

---

## 四、玩家背包与掉落物同步 (Inventory Desync & Entities)

- [ ] **玩家背包更新发包 (Inventory Sync):** 在服务端通过代码直接修改玩家物品（如 `split()`, `shrink()`, `grow()`）后，是否**紧接着**调用了 `player.getInventory().setChanged()` 与 `player.inventoryMenu.broadcastChanges()`？（防"幽灵物品"假象）。
- [ ] **实体物品数据触发 (ItemEntity Sync):** 在修改地上掉落物 `ItemEntity` 的数量时（包括 `shrink()`、`grow()`、`setCount()`），是否调用了 `entity.setItem(entity.getItem().copy())` 来触发底层 `SynchedEntityData` 发包？（防地上的物品数量视觉不同步）。
  > **警示：** `entity.getItem().shrink(n)` 不会自动发包——调用后必须立刻跟 `entity.setItem(entity.getItem().copy())`，否则客户端看到的物品数量不会变化。
- [ ] **满载防炸服切片 (Drop Stack Truncation):** 当 RS 网络和玩家背包双双爆满，不得不把合成产物作为掉落物喷吐时，是否使用了 `getMaxStackSize()` 循环切割拆包？（防单次生成几千个实体引发服务器宕机）。

---

## 五、事务两阶段提交与防刷物 (Ledger Transaction Safety)

- [ ] **共享账本退款隔离 (Double Refund Defense):** 在任务失败（`onBatchFailed`）或者机器中断（`recoverFromPedestals`、`clearFilledPedestals`）时，是否判断了 `if (usingSharedLedger)`？
  > **警示：** 共享账本模式下，退款由链式任务（Chain）统一负责，单个 Delegate 绝对不能将撤下的物品插回网络，否则 100% 触发双倍刷物漏洞。
- [ ] **在线状态核验 (Void Offline Refund):** 在执行最后的 `giveItemToPlayer` 给玩家退回零头前，是否核验了 `player != null && !player.hasDisconnected() && !player.isRemoved()`？（防玩家断线瞬间物品掉入虚空）。
- [ ] **Capability 即时刷新 (LazyOptional Invalidation):** 跨 Tick 的自动合成中，每 Tick 执行 `insertItem` 或 `extractItem` 前，是否**重新获取**了 `BlockEntity` 与其 `IItemHandler`？（绝对禁止跨 Tick 缓存，防方块被挖走导致的刷物/吞物）。
- [ ] **网络失效后退 (Network Fallback):** `abort()` 退款时，是否检查了 `network != null && network.canRun()`？如果网络已失效（控制器被拆除），是否将物品直接退给玩家而非走 RS API？

---

## 六、第三方机器"隐形回档"防御 (Third-Party Ghost Saves)

- [ ] **强制硬盘保存标记 (SetChanged Directive):** 在通过反射或其他手段强行修改了第三方机器（`BlockEntity`）的进度、内部物品或能量池后，是否**必定执行了** `be.setChanged()`？
  > **警示：** 这是防重启/崩服回档的最关键指令，甚至重于客户端发包。
- [ ] **客户端视觉同步 (Block Update):** 修改 BE 的状态会影响方块外观（如祭坛激活/熄灭）时，是否调用了 `level.sendBlockUpdated(pos, oldState, newState, 3)` 强制客户端重绘？
- [ ] **容器伪装兼容性 (Fake Container Typing):** 如果需要借用原版配方系统的副产物机制（`getRemainingItems`），是否使用了原版的 `TransientCraftingContainer` 而不是自定义空壳？（防第三方作者强制类型转换导致的 `ClassCastException`）。
- [ ] **多方块组件类型核验 (Multi-block Component Typing):** 调用第三方 API 获取多方块结构中的"pedestal / 组件"列表时，是否对每个条目做了实际类型校验（如 `isSpiritCrucible` 过滤），而不是盲信返回结果？
  > **警示：** 第三方模组的多方块 API（如 `capturePedestals()`）可能在特定结构配置下意外返回核心机器自身或其他无关方块。每一种组件类型都必须显式过滤，绝不能假设列表里只有正确的 pedestal。

---

## 七、反射与类加载安全 (Reflection & Class Loading)

- [ ] **SRG/MojMap 双名兼容：** 反射访问字段/方法时，是否同时尝试了 Mojang 名和 SRG 名？（MCP 环境下两者不同，漏一种会导致开发/生产不一致。）
- [ ] **Class.forName 缓存：** 所有反射用到的类（`Class<?>` 对象）是否在 `static` 块或 `ensureClasses()` 中一次性加载并缓存，而非在热路径（`tryStartSingleCraft`、`tick()`）中重复调用 `Class.forName`？
- [ ] **`ensureClasses` 模式：** 每个 Delegate 是否有独立的 `ensureClasses()` 方法，且用 `ModClassLoader.ensureClasses(modId, ...)` 先做快速存在性检查再逐项加载？
- [ ] **字段名变更兼容：** 反射读取的字段名是否考虑了第三方模组版本升级导致的字段重命名？（如 Malum 的 `IngredientWithCount` → 在 1.6.6+ 改为公开 API，`getRequiredMaterials` 已做双分支兼容。）

---

## 八、配方解析与递归防护 (Recipe Resolution Safety)

- [ ] **DFS 循环检测 (Cycle Detection):** 递归解析配方依赖树时，是否用 `HashSet<String>` 或 `LinkedHashSet<ResourceLocation>` 维护当前解析路径（压栈/弹栈），并在发现回环时立即跳出？
- [ ] **配方产出物安全判空：** `tryGetResultItem` 后是否检查了 `!result.isEmpty()` 再使用？部分配方（如 SmithingTrim）产出 `ItemStack.EMPTY` 是合法行为。
- [ ] **NBT 匹配精确性 (Ingredient NBT Guard):** 在虚拟库存供给原料时，`Ingredient.test()` 对于有无 NBT 的物品都会通过。是否需要额外的 `matchesIngredientTags()` 检查以避免 NBT 物品被误匹配？（如 RS 存储盘上有 NBT 数据的物品不应被虚拟库存错误消耗。）
- [ ] **副产物同步 (Secondary Outputs):** 多步合成链中，中间产物的 `getCraftingRemainingItem()` 和 `tryGetSecondaryOutputs()` 是否已纳入虚拟库存？（如合成蛋糕返还空桶。）

---

## 九、绑定系统与数据持久化 (Binding & Persistence)

- [ ] **数据本源不可变 (NBT on ItemStack):** 绑定数据是否存储在物品自身的 NBT 上（而非 WorldSavedData）？——物品在哪，数据就在哪，彻底告别世界坏档和跨服数据不同步。
- [ ] **Copy-on-Write 安全 (NBT Deep Copy):** 修改绑定物品的 NBT 时，是否先 `tag.copy()` 再 mutate 再 `stack.setTag(copy)`？（防 JEI 等客户端渲染线程异步读取导致 `ConcurrentModificationException`。）
- [ ] **内存缓存可重建 (Pure Cache):** `AltarBindingRegistry.BINDINGS` 等内存哈希表是否定位为"纯缓存"——即清空后可通过扫描玩家物品 NBT 完全重建？是否在 `ServerStoppedEvent` 时清空？
- [ ] **绑定有效期校验 (Freshness Check):** 使用绑定时是否调用了 `isBindingFresh()` 检查机器仍存在？（但不主动加载区块！）

---

## 十、内存生命周期管理 (Memory Lifecycle & Leaks)

- [ ] **安全并发遍历 (Concurrent Traversal):** 跨 Tick 执行的 `List` 集合（如任务调度器 `AsyncCraftManager`），是否已经使用了 `CopyOnWriteArrayList` 或 snapshot 模式？（防 `ConcurrentModificationException`）。
- [ ] **强加载票据回收 (Ticket Release):** 任何通过 UUID 申请的 `ForgeChunkManager.forceChunk` 凭证，在任务完成、失败、或超时清理时，是否**百分之百**被 `releaseForceLoad` 解除？
- [ ] **生命周期钩子扫除 (Event Hook Cleanup):**
  - 玩家下线（`PlayerLoggedOutEvent`）：定时器缓存是否清除？挂起的 `AsyncCraftChain` 是否取消并退款？远程 GUI 授权（`RemoteGuiAuth`）是否销毁？
  - 服务器停止（`ServerStoppingEvent`/`ServerStoppedEvent`）：所有活跃的合成链是否 `abortAll()`？`BINDINGS` 等静态缓存是否清空？`Diagnostics` 事件是否清空？
- [ ] **静态状态跨服污染 (Cross-Server Leak):** 客户端单例类（如 `MachineHub`）是否在 `ClientPlayerNetworkEvent.LoggingOut` 时清理状态？（加入单机存档 → 退出 → 加入服务器，静态字段仍保留上一个存档的数据。）

---

## 十一、诊断与性能监控 (Diagnostics & Performance)

- [ ] **诊断工具零分配（热路径）：** 从渲染循环或高频 Tick 调用的诊断查询方法（如 `recentEvents()`），是否使用脏标志缓存而非每帧 `List.copyOf()` 创建新列表？
- [ ] **日志字符串无反射：** 性能监控的 `snapshot()` 或高频日志是否使用 `StringBuilder`/`+` 拼接而非 `String.format()` ？（后者走正则 + 反射，极耗 CPU。）
- [ ] **主开关门控：** 所有诊断记录方法体第一行是否为 `if (!enabled) return;`？（默认关闭，零开销。）
- [ ] **环形缓冲区有界：** 诊断事件队列是否有 `MAX_EVENTS` 上限？（`ConcurrentLinkedDeque` + `while (size > MAX) removeFirst()` 模式。）

---

## 十二、配方速率限制与缓存 (Rate Limiting & Caching)

- [ ] **预览请求限流：** 客户端可高频发送的合成预览/计划请求，是否在服务端做了基于玩家 UUID + 时间的限流？（如 `PreviewRateLimiter.isRateLimited(playerId)`。）
- [ ] **绑定扫描缓存：** 高频调用的 `hasAnyBindingForType()` 是否做了 per-player per-tick 缓存？（同一 tick 内只扫一次全部物品槽，后续 O(1) 查 Set。）
- [ ] **配方计划缓存：** 合成计划计算结果是否有短效本地缓存（如 5 秒），避免玩家疯狂点右键导致服务端重复解析同一配方树？

---

> **最后的话：** 当你对着这个清单，自信地全部打上勾的时候，你写出来的模块，就是整个 Minecraft Modding 圈最顶级、最抗造的底层架构。这份清单凝结了数十轮深度审计中踩过的每一个坑——希望它成为你未来每一个新项目的护身符。
