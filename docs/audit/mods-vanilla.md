# mods-vanilla 静态审计

**审计范围**：`mods.vanilla` 及其子包（`brewing`）  
**审计时间**：2026-07-24  
**审计标准**：见 [_AUDIT_STANDARD.md](./_AUDIT_STANDARD.md)

---

## 文件清单

| 路径 | 行数 | 主要职责 |
|------|------|---------|
| `VanillaMachineBatchDelegate.java` | 887 | 原版机器（熔炉/高炉/烟熏炉/营火）批量代理 |
| `CookingMachineBatchDelegate.java` | 242 | 烹饪机器路由器（原版/IronFurnaces 自动选择） |
| `brewing/BrewingStandBatchDelegate.java` | 199 | 酿造台批量代理 |
| `brewing/VanillaBrewingCatalog.java` | ~120 | 原版酿造配方目录 |
| `brewing/VanillaBrewingRecipeDefinition.java` | ~80 | 酿造配方定义 |
| `brewing/VanillaBrewingRecipeHandler.java` | ~150 | 酿造配方处理器 |
| `VanillaFurnaceFuelPolicy.java` | ~180 | 熔炉燃料选择策略 |
| `BrickFurnaceCompat.java` | ~250 | Brick Furnace 兼容层 |
| `CookingMachineFamily.java` | ~60 | 烹饪机器族枚举 |
| `SmithingRecipeHandler.java` | ~100 | 锻造配方处理器 |
| `VanillaMachineRecipeHandler.java` | ~120 | 原版机器配方处理器 |

---

## 发现汇总

| 级别 | 数量 | 概要 |
|------|------|------|
| P0   | 0    | —    |
| P1   | 0    | —    |
| P2   | 1    | VanillaMachineBatchDelegate clearMachineState 输出槽退款守恒破洞 |
| P3   | 3    | 反射字段守卫、燃料退款边界 |

---

## P2 发现（需修复）

### [P2] VanillaMachineBatchDelegate.clearMachineState 输出槽无条件退款 ✅ 已修复

**位置**：`VanillaMachineBatchDelegate.java:798-806`

**修复时间**：2026-07-24

**原问题**：
```java
ItemStack slot2 = furnaceBE.getItem(2);
if (!slot2.isEmpty()) {
    furnaceBE.setItem(2, ItemStack.EMPTY);
    if (refundToRS) refundToRSNetwork(slot2);  // ❌ 输出槽被退款
}
```

**问题描述**：
输出槽（slot 2）的产物是**烧炼完成后的结果**，不属于任何 ledger 管理的材料。当 `clearMachineState` 被 abort 调用时（`refundToRS = player == null`），输出槽的物品会根据 `refundToRS` 决定是否退款。

但在 **离线玩家 abort（`player == null`）** 场景下，输出槽的产物会被退还到 RS 网络。这与输入槽的守恒语义不一致：
- 输入槽（slot 0）只有在 `refundToRS=true` 时才退款（正确，因为它是 ledger 材料）
- **输出槽（slot 2）不应该退款**，因为它是**已经转化的产物**，不是输入材料

**影响场景**：
1. 玩家离线时，batch 被 silent abort
2. 熔炉已经烧炼完成，产物在 slot 2
3. `clearMachineState(be, null)` 被调用
4. 输出槽产物被退还到网络，同时 `collectResult` 也可能收集产物
5. 产物被重复计入（双刷）

**修复方案**：
输出槽产物不应该通过 `clearMachineState` 退款，应该只通过 `collectResult` 正常收集。清空槽位防止残留，但不退款。

```java
// Output slot (slot 2): the transformed result is not a ledger-managed input.
// On abort, clear it to prevent residue, but do NOT refund — collectResult()
// is the only path that should collect the output. Refunding here would risk
// double-collection if collectResult() already ran or races with cleanup.
ItemStack slot2 = furnaceBE.getItem(2);
if (!slot2.isEmpty()) {
    furnaceBE.setItem(2, ItemStack.EMPTY);
    // Do NOT refund output slot to network
}
```

**验证**：已在 VanillaMachineBatchDelegate.java:798-806 应用修复。
if (!slot2.isEmpty() && phase != CraftPhase.DONE) {
    furnaceBE.setItem(2, ItemStack.EMPTY);
    if (refundToRS) refundToRSNetwork(slot2);
}
```

---

## P3 发现（可选优化）

### [P3-1] 反射字段 LIT_TIME_FIELD 无守卫使用

**位置**：`VanillaMachineBatchDelegate.java:432-437`

**现状**：
```java
int litTime = 0;
try {
    if (LIT_TIME_FIELD != null) {
        litTime = LIT_TIME_FIELD.getInt(furnaceBE);
    }
} catch (Exception e) { ... }
```

**观察**：
`LIT_TIME_FIELD` 在静态初始化时解析（L97-109），如果解析失败会是 `null` 并记录警告。使用时有 null 检查，实现正确。

但 L107 的警告消息是：
```java
RSIntegrationMod.LOGGER.warn("[RSI-Vanilla] litTime field not found (no SRG match)");
```

这会在每次启动时打印警告，即使在开发环境下字段名是正确的（`litTime`）。应该改为 `debug` 级别，或者只在两个名称都失败时才 warn。

**建议**：
```java
if (f == null) {
    RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] litTime field not resolved");
}
```

### [P3-2] BrewingStandBatchDelegate.clearMachineState 无退款守卫

**位置**：`BrewingStandBatchDelegate.java:178-186`

**现状**：
```java
protected void clearMachineState(BlockEntity be, ServerPlayer player) {
    if (be instanceof BrewingStandBlockEntity current && placed) {
        current.setItem(0, ItemStack.EMPTY);
        current.setItem(1, ItemStack.EMPTY);
        current.setItem(2, ItemStack.EMPTY);
        current.setItem(3, ItemStack.EMPTY);  // reagent
        current.setItem(4, ItemStack.EMPTY);  // fuel
        current.setChanged();
    }
    placed = false;
    resetState();
}
```

**观察**：
所有槽位都直接清空，没有退款逻辑。这与其他 delegate 的 `clearMachineState` 行为不一致：
- 输入槽（0-2）应该根据 `usingSharedLedger` 决定是否退款
- 试剂（3）和燃料（4）应该无条件退款（out-of-band 材料）

**影响**：
当 brewing 在中途 abort 时（例如外部修改了槽位），所有材料都被丢弃，不退还到网络。

**建议**：
```java
protected void clearMachineState(BlockEntity be, ServerPlayer player) {
    if (be instanceof BrewingStandBlockEntity current && placed) {
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = current.removeItemNoUpdate(slot);
            if (!bottle.isEmpty() && !usingSharedLedger) refundToRSNetwork(bottle);
        }
        ItemStack reagent = current.removeItemNoUpdate(3);
        if (!reagent.isEmpty()) refundToRSNetwork(reagent);
        ItemStack fuel = current.removeItemNoUpdate(4);
        if (!fuel.isEmpty()) refundToRSNetwork(fuel);
        current.setChanged();
    }
    placed = false;
    resetState();
}
```

### [P3-3] VanillaMachineBatchDelegate.refundLeftoverFuel 燃料退款边界模糊

**位置**：`VanillaMachineBatchDelegate.java:508-516`

**现状**：
```java
private void refundLeftoverFuel() {
    if (furnaceBE == null) return;
    ItemStack fuel = furnaceBE.getItem(1);
    if (fuel.isEmpty() || BrickFurnaceCompat.effectiveBurnTicks(
            furnaceBE, fuel, fuelRecipeType()) <= 0) return;
    furnaceBE.setItem(1, ItemStack.EMPTY);
    furnaceBE.setChanged();
    refundToRSNetwork(fuel);
}
```

**观察**：
燃料总是**无条件退款**（`refundToRSNetwork`），不检查 `usingSharedLedger`。这是正确的，因为燃料是 out-of-band 提取的（见 L388-394 和 L410-417），不由 ledger 管理。

但 `effectiveBurnTicks <= 0` 的判断可能漏掉**已经部分燃烧的燃料**：
- 例如：煤炭燃烧时间 1600 tick，已经燃烧了 800 tick
- `effectiveBurnTicks(coal, ...)` 仍然返回 1600（这是**单个煤炭的总燃烧时间**，不是剩余时间）
- 燃料被全额退还（1 煤炭），但实际上只剩一半价值

**当前判断**：
`effectiveBurnTicks <= 0` 只能排除**非燃料物品**（例如石头），不能判断**剩余燃烧时间**。

**影响**：
轻微刷物：已经部分燃烧的燃料被全额退还。

**建议**：
如果需要精确退款，需要从 `litTime` 和 `litDuration` 计算剩余燃烧价值。但这可能过于复杂，且燃料价值较低，当前实现可以接受。

标记为 P3，记录为已知边界行为。

---

## 通用模式观察

### 1. 三种执行模式：FURNACE / CAMPFIRE / VIRTUAL

VanillaMachineBatchDelegate 根据机器类型自动选择执行路径：
- **FURNACE**：物理熔炉，真实槽位操作
- **CAMPFIRE**：营火反射操作（无 BE API）
- **VIRTUAL**：无机器交互（锻造台/切石机），材料提交后立即计算结果

这种多路径设计增加了复杂度，但避免了为每种机器创建独立 delegate。

### 2. 燃料管理策略

`VanillaFurnaceFuelPolicy.select` 实现了确定性燃料选择：
1. 优先使用配置的燃料列表
2. 回退到安全燃料（排除工具/附魔物品）
3. 计算最小燃料数量以覆盖烧炼时间

这比简单的"找任何燃料"更可预测，适合自动化。

### 3. 输出槽清理的双路径问题

多个 delegate 都存在输出槽在 `clearMachineState` 和 `collectResult` 两条路径上的竞态：
- CookingPotBatchDelegate：输出槽 + 显示槽
- VanillaMachineBatchDelegate：输出槽（slot 2）
- BrewingStandBatchDelegate：三个瓶子槽

**建议统一模式**：
- `collectResult` 负责正常完成后的收集
- `clearMachineState` 只负责 abort 时的输入材料退款
- 输出槽在 `clearMachineState` 中**不退款**，只在 `onBatchFinished` 中作为"未收集残留"清理

---

## 安全检查清单

| 检查项 | 状态 | 备注 |
|--------|------|------|
| shared ledger 守卫一致性 | ⚠️ | VanillaMachineBatchDelegate 输出槽需修复（P2） |
| 反射字段访问边界检查 | ✅ | 均有 null 检查 |
| 槽位越界保护 | ✅ | 所有槽位访问都有边界检查 |
| 区块强制加载配对 | ✅ | campfire 路径正确配对 |
| 物品退款守恒 | ⚠️ | 输出槽双路径竞态（P2），brewing 无退款（P3-2） |
| 并发安全（反射初始化） | ✅ | 静态字段在类加载时初始化，线程安全 |
| 燃料自动补充逻辑 | ✅ | 实现确定性策略，边界行为已知（P3-3） |

---

## 架构观察

### CookingMachineBatchDelegate 路由器模式

这个类是一个**代理的代理**，根据机器类型动态选择 VanillaMachineBatchDelegate 或 IronFurnacesBatchDelegate。这种设计：
- **优点**：避免在绑定阶段硬编码机器类型，支持运行时多态
- **缺点**：增加了一层间接调用，所有方法都需要转发
- **风险**：`configureChild()` 需要手动同步父子状态（L81-104），容易遗漏新字段

**建议**：考虑使用工厂模式在 `prepare` 阶段直接返回正确的 delegate，避免运行时转发开销。

---

## 总结

**代码质量**：整体良好，燃料管理和多模式支持实现完善  
**关键风险**：输出槽在 abort 路径上的退款逻辑与守恒语义不一致（P2）  
**优化空间**：brewing 缺失退款逻辑（P3-2），输出槽双路径竞态需要统一处理模式  

**修复优先级**：P2（输出槽退款）需要在下次提交前修复。
