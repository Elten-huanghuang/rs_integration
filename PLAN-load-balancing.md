# 负载均衡 — 忙闲感知 + 智能轮询调度

## 目标

当玩家用同一个 Network Linker 绑定了多台同类型机器时，合成系统自动将任务均分给**空闲**机器并行执行，取代当前"全塞给一台"的行为。

```
现在：4 台熔炉绑好了，需要 64 个铁锭
  → 64 个全部发给熔炉 A，剩下 3 台围观

之后：
  → 4 台各领 16 个，并行跑，耗时 1/4
```

---

## 一、核心设计 — SmartRoundRobin

### 1.1 一句话

> 获取所有绑定同类机器 → 过滤出空闲的 → 均分任务 → 并行派发。全部忙则快速失败。

### 1.2 与现有系统的关系

```
AsyncCraftManager.submit()
    │
    ├─ [旧路径] 单机：选第一台绑定机器 → 创建单 Delegate → 启动链
    │
    └─ [新路径] 多机：LoadBalancer.dispatch()
        │
        ├─ 1. BindingStorage.getBindings(player, modType) → 所有绑定的机器坐标
        ├─ 2. 可用性过滤：level.isLoaded(pos) && !rsi$isBusy()
        ├─ 3. 如果全部忙 → FastFail（返回 NO_IDLE_MACHINE）
        ├─ 4. 均分：total ÷ availableCount → 余数分配给前序节点
        └─ 5. 并行创建 Multiple Delegate → 各自跑
```

---

## 二、接入点 — 在哪里介入

负载均衡不修改任何 Delegate。它在 `AsyncCraftManager` 的 submit 入口和 Delegate 工厂之间插一层：

```
// AsyncCraftManager.submit() 内部，在创建 Delegate 之前

LoadBalancer.DispatchResult result = LoadBalancer.dispatch(player, recipe, totalCount);
switch (result.kind()) {
    case SINGLE_MACHINE:
        // 只有一台或只有一台空闲 → 走原来的单 Delegate 路径
        break;
    case PARALLEL:
        // 多台并行 → 创建多个 Delegate，用 ParallelCraftGroup 管理
        break;
    case NO_IDLE_MACHINE:
        // 全部忙 → 通知上层（RestockManager 下轮重试 / 玩家提示）
        break;
}
```

**三个分发结果**：

| 结果 | 条件 | 行为 |
|------|------|------|
| `SINGLE_MACHINE` | 只有 1 台绑定，或只有 1 台空闲 | 走原路径，单 Delegate |
| `PARALLEL` | ≥ 2 台空闲 | `ParallelCraftGroup` 管理多个 Delegate |
| `NO_IDLE_MACHINE` | 所有绑定机器都忙 | Fast-fail，不挂起 |

---

## 三、数据结构 — ParallelCraftGroup

### 3.1 定义

```java
// crafting/loadbalancer/ParallelCraftGroup.java

public final class ParallelCraftGroup {
    private final ResourceLocation recipeId;
    private final List<ChildBatch> children;
    private int completedCount;
    private boolean anyFailed;

    // 每个子任务
    public record ChildBatch(
        BlockPos machinePos,
        IBatchDelegate delegate,
        int assignedCount        // 这台机器被分配了多少个
    ) {}

    /** 子任务完成回调 — 由每个 Delegate 的 onBatchFinished 中调用 */
    public synchronized void onChildComplete(BlockPos pos, boolean success) {
        if (!success) anyFailed = true;
        completedCount++;
    }

    public synchronized boolean isAllDone() {
        return completedCount >= children.size();
    }
}
```

### 3.2 生命周期

```
LoadBalancer 创建 ParallelCraftGroup
    │
    ├─ 为每台机器创建独立 Delegate
    ├─ 每个 Delegate 的 onBatchFinished → group.onChildComplete(pos, success)
    │
    └─ 所有子任务完成 →
        ├─ 全部成功 → 合并产物 → 写入 RS
        └─ 有失败 → 已完成的产物直接入库；失败的由 Delegate 自身退款
```

**关键设计**：

- **每个子 Delegate 完全独立** — 有各自的 `ExtractionLedger`（非共享），各自负责自己的退款
- **Group 只管"什么时候算完"**，不参与退款逻辑 — 退款由现有 `AbstractBatchDelegate.onBatchFailed` 的模板方法保证
- **产物合并** — 每个子 Delegate 完成时独立 `collectResult`，Group 在全部完成后汇总入库

---

## 四、均分算法 — 按配方执行次数，非物品数

### 4.1 为什么不能按物品数除

```
场景：配方每次产出 4 块玻璃，需要 17 块

按物品数均分 17 ÷ 4 台 = 5,4,4,4
  → 机器 A 拿 5 块 → 要执行 2 次配方（产出 8，超额 3）
  → 机器 B 拿 4 块 → 要执行 1 次配方（刚好）
  → 均分不工整 + 超额产出

按执行次数均分：⌈17/4⌉ = 5 次配方 → 5 ÷ 4 台 = 2,1,1,1
  → 机器 A：执行 2 次 → 产出 8，消耗 2×材料
  → 机器 B/C/D：各执行 1 次 → 各产出 4
  → 总产出 20 块（比需求多 3，进 RS 库存，无浪费）
```

均分粒度的基本单位必须是**配方执行次数（Recipe Operations）**，不能是物品个数。否则遇到输出量 > 1 的配方，切割后的数量无法整除单次配方输出，导致材料投入和产物产出不匹配。

### 4.2 算法

```java
// crafting/loadbalancer/LoadBalancer.java

/**
 * @param totalItems       玩家需要的总物品数
 * @param outputPerCraft   单次配方产出量（从 Recipe 对象读取）
 * @param availableMachines 空闲机器列表
 */
public static List<Assignment> distribute(int totalItems, int outputPerCraft,
                                          List<BlockPos> availableMachines) {
    // Step 1: 换算为配方执行次数（向上取整，允许微量超额）
    int totalOps = (int) Math.ceil((double) totalItems / outputPerCraft);

    // Step 2: 均分执行次数
    int count = availableMachines.size();
    int base = totalOps / count;
    int remainder = totalOps % count;

    // Step 3: 分配
    List<Assignment> assignments = new ArrayList<>(count);
    for (int i = 0; i < count && i < totalOps; i++) {
        int ops = base + (i < remainder ? 1 : 0);
        if (ops > 0) {
            assignments.add(new Assignment(availableMachines.get(i), ops));
        }
    }
    return assignments;
}

public record Assignment(BlockPos pos, int operations) {}
```

```
例：17 个物品、配方每次产出 4 个、3 台空闲机器
  → totalOps = ceil(17/4) = 5
  → distributeOps(5, 3) → 机器A: 2次, 机器B: 2次, 机器C: 1次
  → 总产出次数的物品数 = (2+2+1) × 4 = 20 ≥ 17 ✓
```

### 4.3 上游调用

```java
// LoadBalancer.dispatch() 内部
Recipe<?> recipe = level.getRecipeManager().byKey(recipeId).orElseThrow();
ItemStack resultItem = ModRecipeHandlers.getResultItem(recipe, level.registryAccess());
int outputPerCraft = resultItem.getCount();      // 单次配方产出量

List<Assignment> assignments = distribute(totalItems, outputPerCraft, availableMachines);
```

每个 Assignment 的 `operations` 传给 `Delegates.create(player, recipeId, assignment.operations)` 创建子 Delegate。

---

## 五、可用性过滤

```
for each binding:
    1. level.isLoaded(pos) —— 卸载的机器跳过（不送任务进虚空）
    2. be == null || be.isRemoved() —— 机器被拆了 → 跳过 + warnOnce
    3. ((RSIMachineAccessor) be).rsi$isBusy() —— 正在工作 → 跳过
    4. （可选）validateStructure —— 多方块机器（Malum/Embers/FA）额外验结构完整性
```

`validateStructure` 仅对实现了 `RSIMultiBlockAccessor` 的机器调用，非多方块机器跳过。

---

## 六、产物合并策略

按机器类型分两种：

### 6.1 GUI 容器（熔炉、锅釜等，有 `IItemHandler` 输出槽）

每个子 Delegate 完成时从机器输出槽 `extractItem`，通过 `network.insertItem()` 写回 RS。
Group 不需要额外合并 — 各写各的，RS 自己聚合。

### 6.2 世界交互（祭坛、火盆等，产物在 ItemEntity 或空气中）

每个子 Delegate 完成时扫描 `ItemEntity` 或计算产物，写回 RS。
Group 不需要额外合并 — 同上。

**结论**：Group 只在全部完成后做一个汇总日志 `"[RSI] Parallel craft done: 4/4 succeeded, 192 items returned to RS"`，不参与实际物品处理。

**线程安全**：所有 Delegate 的 `isCraftComplete`、`collectResult`、`onBatchFinished` 回调均由 Forge 服务端主线程的 tick 驱动。`network.insertItem()` 的调用也在同一线程上，不存在并发写入 RS 存储的问题。`synchronized` 关键字仅用于防御性编程，实际运行时不会产生锁竞争。

---

## 七、错误处理

| 场景 | 处理 |
|------|------|
| 子 Delegate A 失败 | `onBatchFailed` 退 A 的材料 → `group.onChildComplete(A, false)` — 不影响 B/C |
| 所有子 Delegate 全失败 | Group 记录 `anyFailed = true` → 汇总日志 warn |
| 并行途中一台机器被拆 | `isCraftComplete` 检测到 `be == null` → 触发 `onBatchFailed`（尝试退款）→ `group.onChildComplete(pos, false)` → 汇总日志标记异常。**不是**"视为完成无产物" |
| 并行途中 chunk 卸载 | `isCraftComplete` 模板方法：`!isLoaded → false`（等待 chunk 加载回来） |
| 材料不够分（RS 库存不足） | `validateAndInit` 阶段检测 → 整个 Group 创建失败，返回 `MATERIAL_SHORTAGE` |

**关于 `be == null` 的处理**：当前 `AbstractBatchDelegate.isCraftComplete()` 在 `be == null || be.isRemoved()` 时直接返回 `true`（视为完成），这在单机场景下会导致已 commit 的材料不明不白消失（机器被拆后槽内物品掉落在地，但账本认为已消耗）。负载均衡不修改基类行为，但在 `ParallelCraftGroup` 层面将 `be == null` 的 mid-craft 情况判定为**子任务失败**（`onChildComplete(pos, false)`），确保汇总日志明确指出材料损失。基类 `isCraftComplete` 的 `be == null → true` 行为作为独立 issue 留待后续修复。

**退款的正确性**：每个子 Delegate 使用独立 `ExtractionLedger`（`usingSharedLedger = false`），所以一个子任务失败只退自己的材料，不会双倍退款也不会连坐其他。

---

## 八、JEI + 播放器感知

### 8.1 JEI `+` 按钮触发

```
玩家在 JEI 点 + 选目标机器 → 下单 64 个

系统行为（不区分手动/自动）：
  - 只有 1 台绑定 → 64 个全给这台
  - 绑了 4 台 → 每台 16 个并行
```

如果指定了特定 modType（如 "malum:spirit_altar"），只在绑定的同型机器间均分；如果选 "Any"，在所有能生产该物品的机器间均分。

### 8.2 合成计划面板（预览）

```
┌── Craft Plan ──────────────────────────────┐
│                                              │
│  Iron Ingot × 64                             │
│  ──── Blast Furnace ────                     │
│  [熔炉 A]  × 16           (4 台均分)          │
│  [熔炉 B]  × 16                              │
│  [熔炉 C]  × 16                              │
│  [熔炉 D]  × 16                              │
│                                              │
└──────────────────────────────────────────────┘
```

当机器数 > 3 时折叠为 `[熔炉 A] × 16 · [熔炉 B] × 16 · (+2 more)`。

---

## 九、配置项

```toml
[loadBalancing]
enableLoadBalancing = true             # 总开关
maxParallelMachines = 16               # 单次最大并行机器数（防极端情况）
requireValidateStructure = true        # 多方块机器过滤时是否验结构完整性
```

---

## 十、不与 Restock 耦合

LoadBalancer 是**独立的中间层**，不依赖 RestockManager：

```
                    ┌─────────────────┐
JEI + 按钮 ────────→│                 │
RestockManager ────→│  AsyncCraftMgr  │── 调用 LoadBalancer 分发
玩家指令 ──────────→│                 │
                    └─────────────────┘
```

所有触发路径（JEI、Restock、未来可能的命令）都经过 `AsyncCraftManager.submit()`，所以只要在 submit 内部接入 LoadBalancer，所有路径自动获得负载均衡能力。

---

## 十一、新增/修改文件清单

### 新增（2 个）

| 文件 | 职责 |
|------|------|
| `crafting/loadbalancer/LoadBalancer.java` | 可用性过滤 + 均分算法 + 分发决策 |
| `crafting/loadbalancer/ParallelCraftGroup.java` | 并行子任务生命周期管理 + 产物汇总 |

### 修改（4 个）

| 文件 | 改动 |
|------|------|
| `crafting/AsyncCraftManager.java` | `submit()` 入口插入 `LoadBalancer.dispatch()` |
| `crafting/plan/CraftPlanScreen.java` | 计划面板：多机展开显示各机份额 |
| `config/RSIntegrationConfig.java` | `[loadBalancing]` 配置段 |
| `mixin/refinedstorage/GridScreenMouseMixin.java` | Grid 右键菜单 JEI `+` 回调走同一 submit 入口 |

### 不改动的文件

| 文件 | 原因 |
|------|------|
| 所有 `*BatchDelegate.java` | 负载均衡在 Delegate **之上**，单个 Delegate 不感知并行 |
| `AbstractBatchDelegate.java` | 不改 — 退款/失败保护是 per-Delegate 的，并行不需要改动 |
| `IBatchDelegate.java` | 不改 |
| `ExtractionLedger.java` | 不改 — 每个子 Delegate 独立账本，无共享冲突 |
| `BindingStorage.java` | 已支持多绑定（`ListTag`），无需改动 |
| `RSIMachineAccessor.java` | `rsi$isBusy()` 已在计划中，无需新增方法 |
| `RSIMultiBlockAccessor.java` | `rsi$validateStructure(level, pos)` 已在计划中 |

---

## 十二、与 PLAN-auto-restock.md 的关系

| 维度 | Auto-Restock | Load-Balancing |
|------|:------------:|:--------------:|
| 解决的问题 | "什么时候补货" | "补货时用哪台机器" |
| 接入点 | `ServerTickEvent` → `AsyncCraftManager.submit()` | `AsyncCraftManager.submit()` 内部 |
| 依赖 | RecipeIndex, RestockStorage, MaterialSources | BindingStorage, RSIMachineAccessor |
| 独立性 | 可独立使用 | 可独立使用 |
| 组合效果 | 自动触发补货 → 自动均分到多台机器 |

两者正交。组合后：RestockManager 检测库存不足 → 调用 submit → LoadBalancer 均分到 N 台空闲机器 → 并行生产。

---

## 十三、边界场景

| 场景 | 处理 |
|------|------|
| 同型 16 台绑了但只有 2 台空闲 | 只用 2 台空闲的，不等那 14 台 |
| 全忙 → FastFail | 返回 `NO_IDLE_MACHINE`。JEI 触发提示玩家"所有 XX 机器正忙"；Restock 等 5s 后重试 |
| 玩家只绑了 1 台（最常见） | `SINGLE_MACHINE` 路径，行为与现在完全一致 |
| 机器 A 和 B 是不同 modType（熔炉 vs 高炉） | 按 modType 分组过滤，不混分 |
| 机器数量 > maxParallelMachines（默认 16） | 截断为前 N 台，warnOnce |
| 子任务 A 的 chunk 被卸载 | `isCraftComplete` 模板方法返回 false，等 chunk 加载回来继续，不影响 B/C |

---

## 十四、实施步骤

### Step 1: LoadBalancer + ParallelCraftGroup（1h）
1. 写 `LoadBalancer.java` — 过滤 + 均分 + 分发决策
2. 写 `ParallelCraftGroup.java` — Group 生命周期
3. 编译验证

### Step 2: AsyncCraftManager 接入（30min）
4. `submit()` 入口插 `LoadBalancer.dispatch()`
5. 三种结果的分支：SINGLE / PARALLEL / NO_IDLE
6. 编译 + 单机回归测试（SINGLE 路径行为不变）

### Step 3: 并行测试（45min）
7. 多机环境：绑 2-4 台同款熔炉
8. JEI `+` 触发 → 确认任务均分 → 确认并行执行
9. 全忙 FastFail → 确认错误消息
10. 中途拆机器 → 确认不崩、其他继续

### Step 4: 合成计划面板（30min）
11. `CraftPlanScreen` 多机展开显示
12. 编译 + 手动验证

### Step 5: 配置 + 文档（15min）
13. 加 `[loadBalancing]` 配置段
14. 更新 README（功能表 + 配置说明）

---

## 十五、不做的

- 不做速度感知加权（TPS 灾难，见讨论）
- 不做跨 Linker/跨网联邦（复杂度失控，刷物风险）
- 不做 pending queue / 挂起等待（Fast-fail 更简洁，见讨论）
- 并行子任务失败后不自动重新分配给其他机器（reassign 的复杂度远大于等下一轮 tick 重试）
- 不做"只绑了 1 台但有其他未绑定同型号机器"的自动发现（绑定的语义是有意选择，不替玩家做决定）
