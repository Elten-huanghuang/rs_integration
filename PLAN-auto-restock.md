    # Auto-Restock — 库存维持 / 自动补货

## 目标

玩家在 RS 侧面板对任意物品设置"维持阈值"后，系统自动检测库存余量，低于阈值时自动触发合成链补货，无需手动操作。

---

## 一、数据模型 — `RestockTask`

### 1.1 数据结构

```java
// crafting/restock/RestockTask.java
public record RestockTask(
    ResourceLocation itemId,      // 目标物品 registry key
    int threshold,                // 维持阈值（库存低于此数量触发补货）
    @Nullable String modTypeId,   // 限定机器类型，null = 任意能生产的机器
    long lastTriggerGameTime,     // 上次触发时间（兜底冷却，主防线是 hasActiveChainFor）
    TaskStatus status             // STOCKED / BELOW / RESTOCKING / ERROR（服务端判定）
) {
    CompoundTag toTag();
    static RestockTask fromTag(CompoundTag tag);
    RestockTask withTriggerTime(long gameTime);
    RestockTask withStatus(TaskStatus newStatus);
}

// 服务端判定后更新 status，不存 batchSize（一次提交全部差额）
```

### 1.2 存储位置

Network Linker 的 NBT — key `rsi_restock_tasks`，`ListTag<CompoundTag>`。与 `aec_bindings` 平级。

```
Linker NBT:
  aec_bindings: [...]          ← 已有
  rsi_restock_tasks: [         ← 新增
    { item: "minecraft:bread", threshold: 64, batch: 16, ... },
    { item: "minecraft:torch", threshold: 128, batch: 32, ... }
  ]
```

语义：任务跟 Linker 走。换 Linker 换任务，和 binding 一致。

### 1.3 配套工具类

```java
// crafting/restock/RestockStorage.java — 读写 Linker NBT
public final class RestockStorage {
    static final String KEY = "rsi_restock_tasks";

    static List<RestockTask> getTasks(ItemStack linker);
    static void setTasks(ItemStack linker, List<RestockTask> tasks);
    static boolean addTask(ItemStack linker, RestockTask task);     // 同 itemId 去重
    static boolean removeTask(ItemStack linker, ResourceLocation itemId);
}
```

---

## 二、调度引擎 — `RestockManager`

### 2.1 触发节拍

服务端 tick 计数器，每 **100 tick（5 秒）** 扫描一次。放在 `ServerTickEvent`：

```java
// 在 RSIntegrationMod 构造器中注册
MOD_BUS.addListener((ServerTickEvent e) -> {
    if (e.phase == TickEvent.Phase.END) {
        RestockManager.onServerTick(e.getServer());
    }
});
```

### 2.2 库存快照缓存（减少 RS API 遍历）

**问题**：RS API 无 O(1) 按 ResourceLocation 查询。`getItemStorageCache().getList().getStacks()` 是 O(n) 全量遍历。如果 10 个玩家各设 10 个任务，每 5 秒就是 100 次遍历。

**方案**：进入 Player 循环时获取**一份全量快照**，后续所有 task 直接从 Map 里 O(1) 查。

`MaterialSources.listAllAvailable()` 已有完全相同的模式（per-player-per-tick 缓存），`RestockManager` 直接复用：

```java
// RestockManager 扫描逻辑
for (ServerPlayer player : onlinePlayers) {
    INetwork network = CraftPacketUtils.resolveNetwork(player);

    // 1. 获取原始快照 (Key = StackKey: item + NBT)
    Map<StackKey, Integer> rawSnapshot = MaterialSources.listAllAvailable(player, network);

    // 2. 聚合为 ResourceLocation → totalCount（O(K)，一次）
    Map<ResourceLocation, Integer> stockByItem = new HashMap<>();
    for (var entry : rawSnapshot.entrySet()) {
        stockByItem.merge(entry.getKey().itemId(), entry.getValue(), Integer::sum);
    }

    // 3. 任务处理 — 真正的 O(1) HashMap lookup
    for (ItemStack linker : findLinkersInInventory(player)) {
        for (RestockTask task : RestockStorage.getTasks(linker)) {
            int currentStock = stockByItem.getOrDefault(task.itemId, 0);
            // ...
        }
    }
}
```

**复杂度**：

| 步骤 | 复杂度 | 说明 |
|------|:------:|------|
| `listAllAvailable()` | O(S) | S = RS 网络中唯一种类数（已缓存，每 player 每 tick 一次） |
| 聚合 `stockByItem` | O(S) | 同上 |
| 每 task 查库存 | **O(1)** | `HashMap.getOrDefault` — 无遍历、无 Stream |

对于 10 玩家 × 10 任务：原始方案 100 次 O(S)，现在 10 次 O(S) + 100 次 O(1)，TPS 开销从"每个 task 一次全量遍历"降为"每个 player 一次全量遍历"。

### 2.3 每次 tick 的处理流程

```
for 每个在线玩家:
    // ── 获取一次快照（内部 per-player-per-tick 缓存）──
    network = resolveNetwork(player)
    stockSnapshot = MaterialSources.listAllAvailable(player, network)
    
    for 背包 + Curios 中的每个 Network Linker:
        if !ENABLE_AUTO_CRAFTING || 玩家无权限: skip
        for 每个 RestockTask in linker:
            
            // 1. 查活跃合成链（主要防护 — 已有链在跑就跳过）
            if AsyncCraftManager.hasActiveChainFor(task.itemId, player): continue
            
            // 2. 冷却兜底（防止 hasActiveChainFor 失效时的极端重复提交）
            if gameTime - task.lastTriggerGameTime < 60秒: continue
            
            // 3. O(1) 查库存快照
            currentStock = stockSnapshot.countMatches(task.itemId)
            if currentStock >= task.threshold: continue
            
            // 4. 查配方可达性
            Recipe<?> recipe = RecipeIndex.findBestRecipe(task.itemId, task.modTypeId)
            if recipe == null:
                task.setStatus(ERROR_NO_RECIPE)
                continue
            
            // 5. 计算补货量（一次提交全部差额，不拆 batch）
            deficit = task.threshold - currentStock
            craftCount = Math.min(deficit, repeatCountMax)
            
            // 6. 触发合成链
            chain = AsyncCraftManager.submit(player, recipe, craftCount)
            task = task.withTriggerTime(gameTime)
            task.setStatus(RESTOCKING)
```

### 2.4 关键约束

| 约束 | 值 | 原因 |
|------|-----|------|
| 扫描间隔 | 100 tick (5s) | 均衡响应速度与 TPS |
| 库存快照 | 每 player 每 tick 缓存一次 | 避免每 task 遍历 RS 存储（MaterialSources 已有此模式） |
| 主要防护 | `hasActiveChainFor()` | 已有活跃链跑同一 item → 跳过（精准，不打乱节奏） |
| 冷却兜底 | 1200 tick (60s) | 极端情况（hasActiveChainFor 漏过、链卡死）的最后防线 |
| 单次上限 | `repeatCountMax` (默认 64) | 防止单次过量请求 |
| 一次提交 | 全部 deficit（不拆 batch） | 合成链本身排队执行，不需要人工分片；冷却从 30s 放宽到 60s 做兜底 |
| 最大任务数 | 每个 Linker 32 个 | 防玩家意外撑爆 NBT |

**冷却机制重新设计**：原方案用 30s 冷却 + batchSize 分段，导致补大额物料（如 1000 个玻璃）需要 30 分钟以上。现方案：
- **主防线** = `hasActiveChainFor()` — 同一 item 有活跃链就不重新提交
- **兜底** = 60s 冷却 — 仅在极端漏过时生效
- **一次提交全部 deficit** — 不拆 batchSize，让 AsyncCraftChain 自己调度

### 2.5 状态恢复 — 链完成回调（立即通知，不等下一次扫描）

**问题**：如果只靠 5 秒一次的 `RestockManager` 扫描来更新状态，链完成后的 0~5 秒内 UI 角标会停留在 `RESTOCKING`（蓝色呼吸），玩家会以为系统卡住了。

**方案**：`AsyncCraftChain` 已有 `onDoneCallback`（链终止时在 `finish()` 中调用）。`RestockManager` 提交链时注册回调，**立即**更新状态并推送 Sync。

```
RestockManager.submit(chain)
    │
    ├─ chain.setOnDoneCallback(() -> {
    │     1. 重新查 RS 库存
    │     2. 更新 task.status = (stock >= threshold) ? STOCKED : BELOW
    │     3. RestockStorage.setTasks(linker, updatedTasks)  // 写 NBT
    │     4. 发 RestockSyncPacket → 客户端立即刷新角标
    │  })
    │
    └─ AsyncCraftManager.submit(chain)
```

**时序对比**：

| 方案 | 状态延迟 | 备注 |
|------|:--------:|------|
| 纯轮询 | 0~5s | 角标停在 RESTOCKING 直到下一次 scan |
| 回调 + 轮询 | **即时** | 回调刷新 UI，轮询做兜底校准 |

回调不代替轮询（库存可能在合成后立刻又被消耗），但**消除视觉延迟**。

### 2.4 `AsyncCraftManager.hasActiveChainFor()` 实现

在 `AsyncCraftManager` 中遍历所有活跃链，检查 chain 的目标产物是否匹配给定 itemId：

```java
public static boolean hasActiveChainFor(ResourceLocation itemId, ServerPlayer player) {
    return ACTIVE_CHAINS.values().stream()
        .anyMatch(c -> c.getOwnerUUID().equals(player.getUUID())
                    && c.getTargetItemId().equals(itemId)
                    && !c.isTerminal());
}
```

`AsyncCraftChain` 中已有的 `getTargetItemId()` 和 `getOwnerUUID()` 可直接复用。如果没有，从 plan 的 `PlanResponse` 中提取产物。

---

## 三、网络协议

### 3.1 `RestockConfigPacket` (C→S)

```java
// 客户端操作
RestockConfigPacket {
    Action action;          // ADD / REMOVE / UPDATE / LIST
    ResourceLocation itemId;
    int threshold;          // ADD/UPDATE 时用
    @Nullable String modTypeId;
}
```

服务端处理：
1. 校验玩家持有 Network Linker
2. 校验 threshold ∈ [1, 4096]
3. 写入 NBT
4. 回发 `RestockSyncPacket` 全量同步

### 3.2 `RestockSyncPacket` (S→C)

```java
RestockSyncPacket {
    List<RestockTask> tasks;  // 当前 Linker 的全部任务
}
```

客户端收到后更新本地缓存，驱动 UI 渲染。

### 3.3 注册

`RSIntegrationNetwork` 中注册两个 packet 的 channel 和 handle。

---

## 四、UI 层

Auto-Restock 的 UI 分两层入口 + 一层管理：

| 层级 | 位置 | 功能 |
|------|------|------|
| **RS Grid 交互**（主入口） | RS 终端 Grid 界面 | 右键物品 → 设置/取消补货，是最自然的操作路径 |
| **侧面板指示**（状态镜像） | RS 侧面板物品图标 | 四态角标，快速扫一眼就知道补货状态 |
| **管理面板**（总览） | Grid/侧面板顶部按钮 → 全屏覆盖 | 一览全部任务，批量编辑 |

---

### 4.1 RS Grid 右键菜单（主入口）

RS Grid 是玩家 90% 时间待的地方。补货任务的增删改应该从这里触发，不是侧面板。

```
RS Grid 物品右键弹出菜单:
 ┌──────────────────────────────┐
 │ [Craft]                      │   ← RS 原生
 │ [Export]                     │   ← RS 原生
 │ ───────────────────────────  │
 │ [✦ Auto-Restock...]          │   ← 新增（无任务时显示）
 │ [✦ Auto-Restock  32/64 ●]   │   ← 新增（已有任务时显示阈值+状态）
 │ [✖ Remove Restock]           │   ← 仅当已有任务时出现
 └──────────────────────────────┘
```

**实现**：`GridScreenMouseMixin` 已有右键处理逻辑，扩展它。右键命中 Grid 物品 slot 时，在原生菜单下方插入分隔线 + 1~2 个 Restock 条目。

点击 `Auto-Restock...` → 弹出新建/编辑浮窗（4.3），与 Grid 保持打开状态（浮窗叠加在 Grid 上方）。

---

### 4.2 侧面板角标（状态镜像）

侧面板不用于触发操作，只用于**快速查看状态**。物品右下角四态色点：

```
┌──────────────┐
│              │
│   [物品图标] │
│          ●   │ ← 6×6 状态色点（右下角）
└──────────────┘
```

| 状态 | 颜色 | 效果 | 含义 |
|------|:----:|------|------|
| `STOCKED` | 绿 `#55FF55` | 常亮 | 库存 ≥ 阈值 |
| `BELOW` | 橙 `#FFAA00` | 常亮 | 库存不足，等待下次 tick 触发 |
| `RESTOCKING` | 蓝 `#55AAFF` | 呼吸闪（800ms 周期） | 合成链执行中 |
| `ERROR` | 红 `#FF5555` | 常亮 | 无配方 / 无可用机器 |

悬停角标显示 tooltip：`"Auto-Restock: 32/64 · Restocking..."`

**实现**：`SidePanelRenderer` 渲染循环中查 `RestockClientCache`，与 4.3 共享同一份客户端缓存。

---

### 4.3 新建/编辑浮窗

从 Grid 右键菜单或管理面板触发，叠加在当前界面之上：

```
      ┌─── Auto-Restock ─────────────────────┐
      │                                        │
      │   [物品大图标]  Iron Ingot             │
      │                                        │
      │   Maintain at least                    │
      │   ┌──────────┐                         │
      │   │    64    │                         │
      │   └──────────┘                         │
      │                                        │
      │   Machine                             │
      │   ┌────────────────────────┐  ┌───┐   │
      │   │ Any available       ▽ │  │ ⟳ │   │
      │   └────────────────────────┘  └───┘   │
      │                                        │
      │   ┌──────────┐  ┌──────────┐          │
      │   │  Confirm  │  │  Cancel  │          │
      │   └──────────┘  └──────────┘          │
      └────────────────────────────────────────┘
```

- **阈值输入框**：范围 [1, 4096]，空值不合法 → Confirm 灰显
- **Machine 下拉**：`Any available`（默认）或列出已绑定且能生产该物品的机器类型
- **⟳**：重新扫描可用机器
- **Enter** = Confirm，**Esc** = Cancel
- Confirm → 发 `RestockConfigPacket(ADD, ...)`，服务端写 NBT 后回发 `RestockSyncPacket`

---

### 4.4 任务管理面板

入口：RS Grid 顶部工具栏新增 `[📋]` 按钮，或侧面板顶部 `[📋]` 按钮。点击打开全屏面板：

```
 ┌── Auto-Restock Management ──── [✕] ────┐
 │                                          │
 │  ┌── Linker: Main RS Network (18 tasks) ┐ │
 │  │                                      │ │
 │  │  🔍 [Filter...]           [Sort ▽]  │ │
 │  │                                      │ │
 │  │  ┌─────────────────────────────────┐ │ │
 │  │  │ [铁锭] Iron Ingot      64 ●    │ │ │
 │  │  │ Current: 58/64    [Edit] [Del] │ │ │
 │  │  ├─────────────────────────────────┤ │ │
 │  │  │ [面包] Bread            32 ●    │ │ │
 │  │  │ Current: 32/32    [Edit] [Del] │ │ │
 │  │  ├─────────────────────────────────┤ │ │
 │  │  │ [玻璃] Glass            128 ⚠   │ │ │
 │  │  │ Current: 12/128  No recipe!     │ │ │
 │  │  ├─────────────────────────────────┤ │ │
 │  │  │ [火把] Torch            64 ⟳    │ │ │
 │  │  │ Current: 18/64  Restocking...   │ │ │
 │  │  └─────────────────────────────────┘ │ │
 │  │                                      │ │
 │  │  [+ Add Task]  [Import from REI]    │ │
 │  └──────────────────────────────────────┘ │
 └──────────────────────────────────────────┘
```

| 功能 | 说明 |
|------|------|
| 搜索 | Filter 输入框，按物品名/ModType 过滤 |
| 排序 | 按名称、按阈值、按当前库存、按状态 |
| 编辑 | `[Edit]` → 弹出 4.3 浮窗，预填值 |
| 删除 | `[Del]` → 确认弹窗 |
| 新增 | `[+ Add Task]` → 物品选择 + 阈值浮窗 |
| 批量导入 | `[Import from REI]` — 从 REI 书签批量导入（后续扩展） |
| 多 Linker | Tab 切换不同 RS 网络的 Linker |

---

### 4.5 客户端缓存

```java
// sidepanel/client/RestockClientCache.java — Grid 和 SidePanel 共享
public enum TaskStatus { STOCKED, BELOW, RESTOCKING, ERROR }

public final class RestockClientCache {
    private static final Map<ResourceLocation, RestockClientEntry> entries = new ConcurrentHashMap<>();

    public static void onSyncPacket(RestockSyncPacket packet);
    public static TaskStatus getStatus(ResourceLocation itemId);
    public static int getThreshold(ResourceLocation itemId);
    public static int getCurrentStock(ResourceLocation itemId);
    public static Map<ResourceLocation, RestockClientEntry> getAllEntries();
    public static void clear();
}

record RestockClientEntry(
    ResourceLocation itemId,
    int threshold,
    int currentStock,
    TaskStatus status,
    @Nullable String modTypeId
) {}
```

Grid 右键菜单和侧面板角标从同一个 `RestockClientCache` 读取，状态一致。

---

### 4.6 实施文件

| 文件 | 改动 |
|------|------|
| `mixin/refinedstorage/GridScreenMouseMixin.java` | Grid 物品右键菜单 "Auto-Restock" / "Remove Restock" |
| `mixin/refinedstorage/GridScreenTooltipMixin.java` | Grid 物品 tooltip 追加补货状态行 |
| `sidepanel/client/SidePanelRenderer.java` | 角标渲染（四态色点） |
| `sidepanel/client/RestockClientCache.java` | **新增** — Grid + SidePanel 共享缓存 |
| `sidepanel/client/RestockConfigScreen.java` | **新增** — 新建/编辑浮窗 |
| `sidepanel/client/RestockManagementScreen.java` | **新增** — 任务管理全屏面板 |

---

## 五、新增/修改文件清单

### 新增（9 个）

| 文件 | 职责 |
|------|------|
| `crafting/restock/RestockTask.java` | 数据模型 + NBT 序列化 |
| `crafting/restock/RestockStorage.java` | Linker NBT 读写 |
| `crafting/restock/RestockManager.java` | Ticker 定时扫描 + 触发逻辑 + 链完成回调 |
| `network/packet/RestockConfigPacket.java` | C→S：增删改任务 |
| `network/packet/RestockSyncPacket.java` | S→C：全量同步（含 TaskStatus） |
| `sidepanel/client/RestockClientCache.java` | Grid + SidePanel 共享缓存 + TaskStatus 枚举 |
| `sidepanel/client/RestockConfigScreen.java` | 新建/编辑浮窗（阈值输入 + Machine 下拉） |
| `sidepanel/client/RestockManagementScreen.java` | 任务管理全屏面板（搜索/排序/编辑/删除） |

### 修改（8 个）

| 文件 | 改动 |
|------|------|
| `crafting/AsyncCraftManager.java` | 暴露 `hasActiveChainFor(itemId, player)` |
| `RSIntegrationMod.java` | 注册 `ServerTickEvent` → `RestockManager.onServerTick` |
| `network/RSIntegrationNetwork.java` | 注册 `RestockConfigPacket` + `RestockSyncPacket` |
| `mixin/refinedstorage/GridScreenMouseMixin.java` | Grid 物品右键菜单 "Auto-Restock" / "Remove Restock" |
| `mixin/refinedstorage/GridScreenTooltipMixin.java` | Grid 物品 tooltip 追加补货状态行 |
| `sidepanel/client/SidePanelRenderer.java` | 角标渲染（四态色点 + tooltip） |
| `config/RSIntegrationConfig.java` | `[autoRestock]` 配置段：总开关、扫描间隔、冷却、最大任务数 |

### 不需改动的文件

| 文件 | 原因 |
|------|------|
| `crafting/AsyncCraftChain.java` | 已有 `onDoneCallback` + `setOnDoneCallback()` — 链完成时自动调用回调 |
| `crafting/AsyncCraftChain.java` → `getTargetItemId()` | 不需要 — RestockManager 通过 callback 闭包捕获 itemId，不走查询路径 |
| `sidepanel/client/SidePanelInputHandler.java` | 不再新增右键菜单（主入口在 Grid） |

---

## 六、边界场景

| 场景 | 处理 |
|------|------|
| 合成链已在跑 | `hasActiveChainFor()` 返回 true → 跳过 |
| 差额 > repeatCountMax | 分批，每次 tick 最多触发 `repeatCountMax` 份 |
| 配方需要 intermediate step | `AsyncCraftChain` 已支持递归，无额外处理 |
| 玩家离线 | 不扫描（合成链在 chunk loaded 时继续执行完） |
| Linker 被移除/丢失 | 任务随 Linker 消失，不影响玩家 |
| 多个 Linker 有重叠任务 | 独立触发，不互斥（两条链同时跑，RS 库存竞争自己消化） |
| 补货物品由多台不同 mod 机器生产 | `modTypeId = null` 时 `RecipeIndex` 按优先级选最优 |
| 补货物品不可达 | 跳过 + 可选 sendSystemMessage 通知一次（warnOnce） |

---

## 七、配置项

```toml
[autoRestock]
enableAutoRestock = true            # 总开关
restockScanIntervalTicks = 100      # 扫描间隔（刻），默认 5 秒
restockCooldownTicks = 1200         # 冷却时间（刻），默认 60 秒（兜底，主防线是 hasActiveChainFor）
restockMaxTasksPerLinker = 32       # 每 Linker 最大任务数
```

---

## 八、与现有系统的关系

| 系统 | 关系 |
|------|------|
| `AsyncCraftManager` | `RestockManager` 是其消费者，只调 `submit()` |
| `AsyncCraftChain` | 复用，无改动 |
| `BindingStorage` | 独立 — Restock 不依赖具体 binding（走 RecipeIndex 找机器） |
| `RecipeIndex` | 查询"谁能生产这个 item" |
| `IBatchDelegate` / `AbstractBatchDelegate` | 不感知 — 合成链内部隐式调用 |
| JEI `+` 按钮 | 并存 — JEI 手动触发 + Restock 自动触发互不干扰 |

---

## 九、实施步骤

### Step 1: 数据层（30min）
1. 写 `RestockTask.java` — record + toTag/fromTag
2. 写 `RestockStorage.java` — Linker NBT 读写
3. 编译验证

### Step 2: 调度引擎（1h）
4. 写 `RestockManager.java` — tick + 扫描 + 触发
5. `AsyncCraftManager` 加 `hasActiveChainFor()`
6. `RSIntegrationMod` 注册 tick handler
7. 编译 + 手动测试

### Step 3: 网络层（30min）
8. 写 `RestockConfigPacket` + handler
9. 写 `RestockSyncPacket` + handler
10. `RSIntegrationNetwork` 注册

### Step 4: UI 层（1h）
11. 写 `RestockClientCache`
12. `SidePanelInputHandler` 加右键菜单
13. `SidePanelRenderer` 加角标
14. 浮窗 EditBox（可复用现有 ModClassLoader 里的简单 UI 模式）

### Step 5: 配置 & 边界测试（30min）
15. 加 config 开关
16. 边界场景遍历

---

## 十、NBT 匹配范围

### 10.1 v1：仅按 itemId 匹配（忽略 NBT）

`RestockTask.itemId` 是 `ResourceLocation`（如 `minecraft:iron_ingot`），库存计数时把同 itemId 的所有 NBT 变体聚合求和。

**适用场景**：铁锭、面包、玻璃等无 NBT 的大宗物料，以及"我不管附魔，有就行"的物品。

**理由**：v1 面向的是补货量最大的通用物料（建材、食物、基础零件），这些物品几乎无 NBT 差异。按 ResourceLocation 聚合可以让 HashMap 直接以 `ResourceLocation` 为 key，实现真正的 O(1) 查找。

### 10.2 后续扩展：严格 NBT 匹配

如果未来需要补货"特定附魔的镐子"或"装了特定流体的单元"，可在 `RestockTask` 增加：

```java
@Nullable CompoundTag requiredNbt;          // null = 忽略 NBT（当前行为）
boolean matchExactNbt = true;               // 严格 NBT 匹配 vs 忽略
```

对应的改动：
1. 库存计数阶段：不聚合，保留 `Map<StackKey, Integer>` → 用完整 `StackKey`（itemId + NBT）查
2. 配方查找：`RecipeIndex` 需支持"NBT 输出"的匹配（部分 mod（如 SlashBlade）已有 NBT 配方处理）
3. 合成触发：`AsyncCraftChain` 传入 target NBT，产物入库时校验

**复杂度很高，v1 不做。** 90% 的补货需求是无 NBT 的通用材料。

---

## 十一、不做的

- 不实现"跨玩家 restock"（一个玩家给另一个玩家补货 — 太复杂）
- 不实现"自动创建 recipe pattern"（RS 原生的 autocrafting 已经有 Processing Pattern）
- 不实现 fluid restock（先只做物品）
- 不做 JEI/REI 入口（右键菜单够用，后续可加）
- v1 不做 NBT 严格匹配
