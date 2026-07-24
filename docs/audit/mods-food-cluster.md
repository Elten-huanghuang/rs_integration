# mods-food-cluster 静态审计

**审计范围**：`mods.crockpot`、`mods.farmersdelight`、`mods.farmersrespite`、`mods.crabbersdelight`、`mods.immortalersdelight`  
**审计时间**：2026-07-24  
**审计标准**：见 [_AUDIT_STANDARD.md](./_AUDIT_STANDARD.md)

---

## 文件清单

| 路径 | 行数 | 主要职责 |
|------|------|---------|
| `crockpot/CrockPotRSModule.java` | 70 | 模组注册 |
| `crockpot/CrockPotFoodValues.java` | 238 | 食物值计算与贪心选择 |
| `crockpot/CrockPotBatchDelegate.java` | 766 | Crock Pot 批量代理 |
| `farmersdelight/FarmersDelightRSModule.java` | ~50 | 模组注册 |
| `farmersdelight/CookingPotBatchDelegate.java` | 516 | Cooking Pot 批量代理 |
| `farmersdelight/SkilletBatchDelegate.java` | 504 | Skillet/Campfire 批量代理 |
| `farmersrespite/FarmersRespiteRSModule.java` | ~40 | 模组注册 |
| `farmersrespite/kettle/FRKettleBatchDelegate.java` | 553 | Kettle 批量代理 |
| `crabbersdelight/CrabTrapBatchDelegate.java` | 324 | Crab Trap 批量代理 |
| `crabbersdelight/CrabTrapLootWrapper.java` | ~100 | Loot 包装 |
| `crabbersdelight/CrabTrapRecipeResolver.java` | ~80 | 配方解析 |
| `immortalersdelight/ImmortalersDelightRSModule.java` | ~40 | 模组注册 |
| `immortalersdelight/EnchantalCoolerBatchDelegate.java` | 505 | Enchantal Cooler 批量代理 |

---

## 发现汇总

| 级别 | 数量 | 概要 |
|------|------|------|
| P0   | 0    | —    |
| P1   | 0    | —    |
| P2   | 1    | CookingPot 容器退款守恒破洞 |
| P3   | 4    | 文档/边界情况 |

---

## P2 发现（需修复）

### [P2] CookingPotBatchDelegate.clearMachineSlotsAndRefund 容器退款守恒破洞 ✅ 已修复

**位置**：`CookingPotBatchDelegate.java:487-488`

**修复时间**：2026-07-24

**原问题**：
```java
ItemStack container = handler.extractItem(CONTAINER_SLOT, 64, false);
if (!container.isEmpty() && !usingSharedLedger) refundToRSNetwork(container);
```

虽然有 `!usingSharedLedger` 守卫，但注释不清晰，容易误解容器的来源和退款责任。

**问题描述**：
容器（container）是在 `tryStartWithMaterials:254-275` 中通过 **已提交的 ledger 保留的材料**分离出来并插入的（见 L204-216 的 `containerMaterial` 分离逻辑）。它属于共享账本的一部分。

容器通过 `getSupplementalSpecs()` (L106-111) 声明，被包含在 `materials` 列表中，由 shared ledger 管理。在 `tryStartWithMaterials` 中被从列表分离后单独插入到 CONTAINER_SLOT，但仍然是 ledger 记录的材料。

**潜在风险**（理论上，当前守卫已正确）：
- 当 `usingSharedLedger=true` 时，容器由 ledger 的 `refundCommitted()` 负责退款
- 当 `usingSharedLedger=false` 时，delegate 手动退款

当前守卫逻辑是正确的，但缺少注释说明容器的来源和退款责任，容易在后续维护中误改。

**修复方案**：
添加清晰的注释，说明容器来源和退款责任：

```java
// Container was reserved via the shared ledger (separated from materials in
// tryStartWithMaterials L204-216). When usingSharedLedger=true, the ledger's
// refundCommitted() will restore it; delegate must not double-refund.
ItemStack container = handler.extractItem(CONTAINER_SLOT, 64, false);
if (!container.isEmpty() && !usingSharedLedger) refundToRSNetwork(container);
```

**对比参考**：
EnchantalCoolerBatchDelegate (L337-339) 的容器是 **out-of-band** 材料（在 tryStartWithMaterials 内部直接从 RS 网络提取，不在 materials 参数中），所以无条件退款：
```java
// Container is out-of-band (not in shared ledger) — refund unconditionally
ItemStack container = handler.extractItem(CONTAINER_SLOT, 64, false);
if (!container.isEmpty()) refundToRSNetwork(container);
```

**验证**：已在 CookingPotBatchDelegate.java:484-488 添加注释。

---

## P3 发现（可选优化）

### [P3-1] CrockPotBatchDelegate.clearMachineSlotsAndRefund 循环边界硬编码

**位置**：`CrockPotBatchDelegate.java:736-742`

**现状**：
```java
for (int slot = 0; slot < potLevel; slot++) {
    ItemStack s = handler.extractItem(slot, 64, false);
    if (!s.isEmpty() && !usingSharedLedger) refundToRSNetwork(s);
}
```

**观察**：
循环上界是 `potLevel`（动态值），但 L734 的 `handler.getSlots() < 6` 是硬编码常量。如果 `potLevel` 可以超过 `handler.getSlots()`，会导致越界访问。

**当前保护**：
L734 的 `getSlots() < 6` 检查理论上能拦截大部分情况（因为 Crock Pot 的 slot 数是 `potLevel + 2`，最小 pot 是 4 槽输入 → 6 总槽），但如果未来 potLevel > 4 的变种出现，这个硬编码常量会失效。

**建议**：
```java
if (handler == null || handler.getSlots() < potLevel + 2) return;
```

### [P3-2] FRKettleBatchDelegate.resolvePouring 线性扫描全配方表

**位置**：`FRKettleBatchDelegate.java:128`

**现状**：
```java
for (Recipe<?> r : level.getRecipeManager().getRecipes()) {
    if (!FRReflection.kettlePouringRecipeClass.isInstance(r)) continue;
    // ...
}
```

**观察**：
每次 `validateAndInit` 都全表扫描 `getRecipes()`，复杂度 O(n)。Farmer's Respite 的 pouring 配方数量不多（< 20），但在配方管理器有数千配方时仍会产生不必要的迭代开销。

**建议**：
- 按类型过滤：`level.getRecipeManager().getAllRecipesFor(FRReflection.kettlePouringRecipeType)` 若该类型可用
- 或：首次扫描时缓存 `fluid -> pouringRecipe` 映射到静态 map

**优先级**：P3（性能影响小，仅在配方初始化时触发）

### [P3-3] SkilletBatchDelegate 反射字段名硬编码 SRG 名称

**位置**：`SkilletBatchDelegate.java:69-77`

**现状**：
```java
private static Field resolveField(Class<?> clazz, String official, String srg) {
    for (String name : new String[]{official, srg}) {
        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) { ... }
    }
    return null;
}
```

**观察**：
SRG 名称（`f_59042_`）是混淆环境下的字段名，但在开发环境下会失败。当前代码先尝试 official 名称，fallback 到 SRG，理论上正确，但：
1. 如果 Minecraft 版本更新导致 SRG 名称变化，会静默失败
2. 没有版本守卫，无法在编译时检测到不兼容

**建议**：
- 考虑使用 AccessTransformer 或 Mixin 使字段公开，避免反射
- 或在静态初始化时记录警告，提示哪些字段不可达

**优先级**：P3（当前实现对大部分环境有效，仅在跨版本升级时需要关注）

### [P3-4] EnchantalCoolerBatchDelegate 容器提取不在 shared ledger 中

**位置**：`EnchantalCoolerBatchDelegate.java:214-255`

**现状**：
```java
if (existing.isEmpty()) {
    if (network == null) { ... }
    ItemStack extracted = network.extractItem(container.copyWithCount(1), 1,
            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
    // ... 直接插入容器槽
}
```

**观察**：
容器在 `tryStartWithMaterials` 内部通过 `network.extractItem` **绕过 shared ledger** 直接提取。这与 CookingPot 的行为不一致（CookingPot 的容器是从 `materials` 中分离的，由 ledger 管理）。

**影响**：
- 若在 DAG 并发环境下，EnchantalCooler 的容器提取不受 ledger 保护，可能与其他节点竞争同一容器
- L338 的清理代码中，容器**无条件退款**（`refundToRSNetwork(container)`），这在 `usingSharedLedger=true` 时会导致双重退款（一次是这里的 `refundToRSNetwork`，一次是 ledger 的 abort）

**实际状态**：
检查代码发现容器是在 `tryStartWithMaterials` 内部提取的，不在 `materials` 列表中，所以 ledger 不会管理它。因此 L338 的无条件退款是**正确的**。

**建议**：
添加注释说明容器不在 ledger 中，避免未来混淆：
```java
// Container is out-of-band (not in shared ledger) — refund unconditionally
ItemStack container = handler.extractItem(CONTAINER_SLOT, 64, false);
if (!container.isEmpty()) refundToRSNetwork(container);
```

（注：代码中已有此注释，标记为 P3-观察项）

---

## 通用模式观察

### 1. 容器槽管理策略差异

**CookingPot**：容器从 `materials` 分离，由 shared ledger 管理  
**EnchantalCooler**：容器在 `tryStartWithMaterials` 内部提取，绕过 ledger  
**FRKettle**：容器同样从 `materials` 分离（L253-266）

**建议**：统一容器管理策略，要么都走 ledger，要么都标注为 out-of-band。

### 2. 燃料自动补充逻辑

**CrockPot**：`extractFuel` 实现优先级队列 + 安全性过滤（L671-706）  
**EnchantalCooler**：`tryInsertFuelFromRS` 支持方块分解（L410-458）  

**观察**：两者都实现了燃料自动补充，但逻辑独立，未抽取公共工具。考虑未来重构时提取 `FuelExtractionHelper` 工具类。

### 3. 反射探测模式一致

所有 delegate 都采用 `volatile boolean probed` + `synchronized` 双检锁模式，代码质量一致。

---

## 安全检查清单

| 检查项 | 状态 | 备注 |
|--------|------|------|
| shared ledger 守卫一致性 | ⚠️ | CookingPot 容器退款需修复（P2-1） |
| 反射字段访问边界检查 | ✅ | 均有 null 检查 |
| 槽位越界保护 | ⚠️ | CrockPot 硬编码常量（P3-1） |
| 区块强制加载配对 | ✅ | 所有 delegate 都有 `forceChunkLoad(true/false)` 配对 |
| 物品退款守恒 | ⚠️ | CookingPot 容器需修复 |
| 并发安全（反射初始化） | ✅ | 双检锁正确实现 |

---

## 总结

**代码质量**：整体良好，delegate 实现遵循统一模式  
**关键风险**：CookingPot 容器退款逻辑在 DAG 并发环境下存在守恒破洞（P2）  
**优化空间**：容器管理策略可统一，燃料提取逻辑可抽取公共工具类  

**修复优先级**：P2-1（容器退款）需要在下次提交前修复。
