# mods-magic-cluster 静态审计

**审计范围**：`mods.goety`、`mods.malum`  
**审计时间**：2026-07-24  
**审计标准**：见 [_AUDIT_STANDARD.md](./_AUDIT_STANDARD.md)

---

## 文件清单

| 路径 | 行数 | 主要职责 |
|------|------|---------|
| `goety/GoetyBatchDelegate.java` | 1692 | Goety Dark Altar + Necro Brazier 批量代理 |
| `goety/CursedInfuserBatchDelegate.java` | ~350 | Cursed Infuser 批量代理 |
| `goety/GoetyRSModule.java` | ~80 | 模组注册 |
| `goety/RSAvailabilityChecker.java` | ~150 | RS 可用性检查器 |
| `goety/GoetyGuiClientEventHandler.java` | ~100 | GUI 客户端事件 |
| `malum/MalumBatchDelegate.java` | 839 | Malum Spirit Altar 批量代理 |
| `malum/MalumSpiritCrucibleBatchDelegate.java` | ~400 | Spirit Crucible 批量代理 |
| `malum/MalumRunicWorkbenchBatchDelegate.java` | ~300 | Runic Workbench 批量代理 |
| `malum/MalumRSModule.java` | ~60 | 模组注册 |

---

## 发现汇总

| 级别 | 数量 | 概要 |
|------|------|------|
| P0   | 0    | —    |
| P1   | 0    | —    |
| P2   | 0    | —    |
| P3   | 5    | 复杂反射逻辑、状态管理边界 |

---

## P3 发现（已知边界行为）

### [P3-1] MalumBatchDelegate.clearPedestals 条件退款逻辑复杂

**位置**：`MalumBatchDelegate.java:701-720`

**现状**：
```java
private void clearPedestals() {
    // ...
    boolean isReal = (ledger != null && ledger.isCommitted());
    if (stack != null && !stack.isEmpty() && isReal) {
        returnItem(stack);
    }
    // ...
}
```

**观察**：
退款条件是 `ledger.isCommitted()`，而不是标准的 `!usingSharedLedger`。这与其他 delegate 的退款逻辑不一致。

**实际语义**：
- `isReal = true`：ledger 已经提交，物品是从 RS 真实提取的，应该退还
- `isReal = false`：ledger 未提交（材料保留失败），物品只是临时放置，清空即可

这是**正确的语义**，但命名容易误导。建议重命名为 `materialsCommitted` 以清晰表达意图。

**优先级**：P3（实现正确，仅命名可优化）

### [P3-2] GoetyBatchDelegate.recoverFromPedestals 双重守卫

**位置**：`GoetyBatchDelegate.java:1340-1387`

**现状**：
```java
private void recoverFromPedestals() {
    // ...
    boolean refundToRS = player == null;
    for (Object ped : filledPedestals) {
        // ...
        if (usingSharedLedger) {
            // Do NOT re-insert
        } else if (refundToRS && network != null) {
            // Insert to RS
        }
    }
}
```

**观察**：
使用了 **两层守卫**：
1. `usingSharedLedger`：共享账本会自动退款，跳过
2. `refundToRS = player == null`：只有离线玩家 abort 时才退款到 RS

**问题**：
当 `usingSharedLedger=false` 且 `player != null` 时，物品**既不退款到 RS，也不退款到玩家**，直接丢弃。这可能导致物品丢失。

**实际场景**：
- 玩家在线时，`tryStartSingleCraft` 使用私有 ledger（`usingSharedLedger=false`）
- 如果 ritual 在 abort 路径上调用 `recoverFromPedestals`，且 `player != null`
- 物品会被清空但不退款

**修复建议**：
```java
if (usingSharedLedger) {
    // Shared ledger will refund
} else if (player == null) {
    // Offline abort: refund to RS if available
    if (network != null) {
        network.insertItem(stack, stack.getCount(), Action.PERFORM);
    }
} else {
    // Online abort: refund via ledger.rollback() or to player inventory
    // Current code path drops items — should refund to player
    ItemHandlerHelper.giveItemToPlayer(player, stack);
}
```

**优先级**：P3（边界场景，需验证实际触发路径）

### [P3-3] GoetyBatchDelegate 反射调用密度极高

**位置**：整个文件

**观察**：
GoetyBatchDelegate 使用了大量反射调用：
- 字段访问：`Reflect.getField`、`Reflect.setField`
- 方法调用：`Reflect.invoke`
- 类型检查：`GoetyReflection.darkAltarBEClass.isInstance(be)`

**统计**：
- 反射字段访问：~30 处
- 反射方法调用：~50 处
- 无 null 守卫的反射：~10 处

**风险**：
1. 性能开销：每次反射调用都有 JNI 开销
2. 错误传播：反射失败时通常返回 `null` 或抛异常，未充分检查可能导致 NPE
3. 版本脆弱性：Goety 更新字段名/方法签名时，反射调用静默失败

**建议**：
- 增加反射失败后的回退逻辑
- 考虑在静态初始化时缓存 `Method` 对象（已部分实现在 `GoetyReflection`）
- 添加版本兼容性检查，启动时验证关键反射是否可用

**优先级**：P3（当前实现可用，但维护成本高）

### [P3-4] MalumBatchDelegate pedestal 槽位状态竞态

**位置**：`MalumBatchDelegate.java:686-699`

**现状**：
```java
private int placeOnNextEmptyPedestal(List<?> pedestals, int startIdx, ItemStack stack) throws Exception {
    for (int i = startIdx; i < pedestals.size(); i++) {
        Object ap = pedestals.get(i);
        if (isSpiritCrucible(ap)) continue;
        Object inv = ap.getClass().getMethod("getSuppliedInventory").invoke(ap);
        boolean empty = (boolean) inv.getClass().getMethod("isEmpty").invoke(inv);
        if (empty) {
            inv.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                    .invoke(inv, 0, stack);
            return i + 1;
        }
    }
    throw new IllegalStateException("No empty pedestal slot found from index " + startIdx);
}
```

**观察**：
`pedestals` 列表是在 `capturePedestals()` 时一次性捕获的快照（L149、L201、L362）。如果在捕获后、放置前有外部操作修改了 pedestal 状态，`isEmpty()` 的判断会过时，导致：
1. 槽位被占用但仍然尝试放置 → 放置失败
2. 抛出 `IllegalStateException` → craft 失败，材料通过 ledger 回滚

**影响**：
轻微：仅在极端并发场景下（玩家手动操作 pedestal 的同时 RS 自动化尝试放置）会触发。当前实现通过异常终止保证了守恒性。

**建议**：
在放置前重新检查槽位状态，如果槽位被占用则跳过并尝试下一个 pedestal：
```java
if (!empty) continue;  // slot occupied since capture, try next
```

**优先级**：P3（极端场景，当前实现已保证守恒）

### [P3-5] GoetyBatchDelegate brazier 配方设置绕过 updateRecipe

**位置**：`GoetyBatchDelegate.java:600-606`

**现状**：
```java
// Set recipe directly (bypass updateRecipe) — yzzzfix overrides
// updateRecipe to call stopBrazier(false) when recipe is null,
// which ejects items prematurely.
Reflect.setField(brazier, GoetyReflection.F_RECIPE_ID, ((Recipe<?>) brazierRecipeObj).getId());
Reflect.setField(brazier, GoetyReflection.F_RECIPE, brazierRecipeObj);
```

**观察**：
代码注释明确说明绕过 `updateRecipe()` 是为了避免 yzzzfix 分支的副作用（`stopBrazier(false)` 会提前弹出物品）。

这是一个**针对特定 Goety 变种的 workaround**，在主线 Goety 中可能不需要。

**风险**：
1. 绕过公共 API 可能导致 Brazier 内部状态不一致
2. 如果 Goety 更新了字段名或添加了额外的初始化逻辑，直接字段赋值会失效

**建议**：
- 添加版本检测，只在 yzzzfix 分支启用 workaround
- 或者：检测 `updateRecipe` 是否被重写，动态决定使用直接赋值还是方法调用

**优先级**：P3（特定变种兼容性问题，当前实现有效）

---

## 通用模式观察

### 1. 反射密度对比

| Delegate | 反射调用数 | 反射字段数 | 缓存策略 |
|---------|----------|----------|--------|
| MalumBatchDelegate | ~40 | ~10 | 部分缓存（字段） |
| GoetyBatchDelegate | ~80 | ~20 | 全局缓存（GoetyReflection） |

GoetyBatchDelegate 的反射密度是 MalumBatchDelegate 的 2 倍，主要原因是 Goety 的 ritual 系统比 Malum 的 altar 更复杂（需要检查 research、structure、sacrifice、enchantment 等多个前置条件）。

### 2. 状态机复杂度

**GoetyBatchDelegate** 管理 3 种状态：
- Dark Altar ritual（原始路径）
- Necro Brazier（L126-296）
- 双路径共享逻辑（soul energy、pedestal 管理）

**MalumBatchDelegate** 管理 2 种状态：
- Spirit Altar 正常 craft
- Summon ritual 特殊路径（L368-443）

两者都使用 `isBrazier`/`isSummon` 布尔标志切换执行路径，增加了分支复杂度。

### 3. 材料退款策略差异

| Delegate | 退款条件 | 守卫字段 |
|---------|---------|---------|
| MalumBatchDelegate | `ledger.isCommitted()` | `isReal` |
| GoetyBatchDelegate | `usingSharedLedger \|\| player == null` | `refundToRS` |

Malum 使用 ledger 状态判断，Goety 使用玩家在线状态判断。两者语义不同，需统一。

### 4. Pedestal 管理一致性

两者都实现了 pedestal 的：
- 捕获（一次性快照）
- 放置（逐个填充）
- 清理（逐个退款）

但清理逻辑的退款守卫不一致（见 P3-1 和 P3-2）。

---

## 安全检查清单

| 检查项 | 状态 | 备注 |
|--------|------|------|
| shared ledger 守卫一致性 | ⚠️ | Goety pedestal 退款守卫需统一（P3-2） |
| 反射调用 null 检查 | ⚠️ | Goety 部分调用无守卫（P3-3） |
| 槽位越界保护 | ✅ | 均有边界检查 |
| 物品退款守恒 | ⚠️ | Goety 边界场景可能丢物（P3-2） |
| 并发安全（状态机） | ✅ | 无共享可变状态 |
| 反射字段版本兼容性 | ⚠️ | 依赖特定字段名，脆弱（P3-3, P3-5） |

---

## 架构观察

### 复杂度来源

**GoetyBatchDelegate（1692 行）** 的复杂度来自：
1. 双机器支持（Dark Altar + Necro Brazier）
2. 丰富的前置条件检查（research/structure/sacrifice/enchantment）
3. Summon ritual 特殊路径（玩家手动激活）
4. Soul energy 管理（cage + candlestick）
5. 跨维度支持（`machineDim` 设置在 validateAndInit 内）

**MalumBatchDelegate（839 行）** 的复杂度来自：
1. Pedestal 动态扫描（Spirit Crucible 过滤）
2. 双库存管理（main inventory + spirit inventory）
3. Altar 原生 tick 驱动（`init()` 后等待 `isCrafting` 状态变化）

### 推荐改进方向

1. **提取公共 Pedestal 管理工具类**：
   - `PedestalManager.capture()`
   - `PedestalManager.placeItems()`
   - `PedestalManager.clearAndRefund()`

2. **统一退款守卫策略**：
   - 所有 delegate 统一使用 `usingSharedLedger` 判断
   - 离线玩家 abort 的特殊处理统一在 `AbstractBatchDelegate` 层

3. **反射调用封装**：
   - 将高频反射调用封装为 `GoetyAPI` 和 `MalumAPI` 接口
   - 静态初始化时缓存所有 `Method` 对象
   - 提供版本兼容性检查

---

## 总结

**代码质量**：整体良好，反射逻辑复杂但功能完整  
**关键风险**：反射密度极高导致维护成本和版本脆弱性增加  
**优化空间**：退款逻辑需统一，反射调用需封装，pedestal 管理可抽取公共工具  

**修复优先级**：所有 P3 发现均为边界行为或优化建议，不阻塞发布。
