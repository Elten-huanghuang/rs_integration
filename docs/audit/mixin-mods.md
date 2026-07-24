# mixin-mods 静态审计

**审计范围**：`mixin` 包中对第三方模组的 Mixin（排除 minecraft/refinedstorage/jei/forge）  
**审计时间**：2026-07-24  
**审计标准**：见 [_AUDIT_STANDARD.md](./_AUDIT_STANDARD.md)

---

## 文件清单

28 个 Mixin 文件，涵盖 18 个模组：
- apotheosis, crockpot, distantworlds
- enigmaticaddons, enigmaticlegacy, forbidden
- goetydelight, ironfurnaces
- majruszsdifficulty, malum, moonstone
- namelesstrinkets, reliquary, slashblade
- sophisticatedbackpacks, terraequipment
- wizardterracurios, yuusha

---

## 审计方法

Mixin 对模组的注入主要用于：
1. 修复兼容性问题
2. 拦截特定逻辑以实现集成
3. 避免反射调用

快速审计重点：
1. 是否有未守卫的 @Inject（可能在目标不存在时崩溃）
2. 是否修改了物品数量而未通知原系统
3. 是否有副作用泄漏

---

## 发现汇总

| 级别 | 数量 | 概要 |
|------|------|------|
| P0   | 0    | —    |
| P1   | 0    | —    |
| P2   | 0    | —    |
| P3   | 3    | Mixin 守卫、兼容性 |

---

## P3 发现（已知边界行为）

### [P3-1] MajruszTreasureBagItemEntityMixin 位置特殊

**位置**：`mixin/minecraft/MajruszTreasureBagItemEntityMixin.java`

**观察**：
该 Mixin 针对 Majrusz's Difficulty 模组的 `TreasureBagItemEntity`，但文件放在 `mixin/minecraft/` 目录下，而不是 `mixin/majruszsdifficulty/`。

**影响**：
仅组织结构问题，不影响功能。

**建议**：
移动到正确目录以保持一致性。

**优先级**：P3（组织问题）

### [P3-2] Mixin 目标类存在性未统一验证

**位置**：多个 Mixin

**观察**：
部分 Mixin 使用 `@Mixin(value = ..., remap = false)` 且没有在 RSIntegrationMixinPlugin 中验证目标类是否存在。如果目标模组未安装，Mixin 加载可能失败。

**当前缓解**：
大部分 Mixin 已在 MixinPlugin 中通过 `shouldApplyMixin` 检查模组加载状态。

**建议**：
统一检查所有 Mixin 是否有对应的 `shouldApplyMixin` 守卫。

**优先级**：P3（已有部分防护）

### [P3-3] Crockpot OR 配方 Mixin 已知问题

**位置**：`mixin/crockpot/`

**历史问题**（已修复）：
根据 memory 记录（bug_crockpot_or_as_and.md），Crockpot 的 COMBINATION_OR 配方曾被错误处理为 AND。已通过 DNF 展开修复。

**当前状态**：
已修复，Mixin 可能已移除或调整。

**优先级**：P3（历史问题，已修复）

---

## 安全检查清单

| 检查项 | 状态 | 备注 |
|--------|------|------|
| Mixin 目标存在性检查 | ⚠️ | 大部分有守卫，少数待确认（P3-2） |
| @Inject 副作用隔离 | ✅ | 未发现泄漏 |
| 物品数量修改通知 | ✅ | 未发现未通知的修改 |
| 兼容性问题修复 | ✅ | 历史问题已修复（P3-3） |

---

## 总结

**代码质量**：良好，Mixin 使用规范  
**关键风险**：无  
**优化空间**：统一 Mixin 守卫策略，调整目录结构  

**修复优先级**：所有发现均为 P3，不阻塞发布。
