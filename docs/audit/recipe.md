# recipe 静态审计

**审计范围**：`recipe` 包（配方处理器）  
**审计时间**：2026-07-24  
**审计标准**：见 [_AUDIT_STANDARD.md](./_AUDIT_STANDARD.md)

---

## 文件清单

23 个配方处理器文件，主要包括：
- `ModRecipeHandlers.java` - 配方处理器注册中心
- `AbstractRecipeHandler.java` - 抽象基类
- 各模组专用处理器（CrockPot, Goety, Malum, Aetherworks 等）

---

## 审计方法

配方处理器主要负责从 Recipe 对象提取 IngredientSpec 列表，属于**数据转换层**，不涉及物品守恒或状态管理。快速审计重点：
1. 是否正确处理空配方/空 ingredient
2. 是否正确计算 count
3. 是否有未捕获的反射异常

---

## 发现汇总

| 级别 | 数量 | 概要 |
|------|------|------|
| P0   | 0    | —    |
| P1   | 0    | —    |
| P2   | 0    | —    |
| P3   | 2    | 缓存策略、异常处理 |

---

## P3 发现（已知边界行为）

### [P3-1] ModRecipeHandlers 缓存策略按类不按实例

**位置**：`ModRecipeHandlers.java`

**观察**：
配方处理器缓存是按 `Recipe.class` 作为 key，而不是 `recipeId`。这意味着：
- 同一个 Recipe 类的所有实例共享同一个 handler
- 如果某个 handler 的 `canHandle()` 方法对不同实例返回不同结果，缓存会失效

**实际案例**（已知问题）：
GoetyRecipeHandler 过滤 Convert/Teleport ritual，但它们与普通 ritual 共享 `RitualRecipe.class`。第一个被查询的 ritual 决定了整个类的缓存结果。

**当前缓解**：
GoetyBatchDelegate.getRequiredMaterials() 绕过了 ModRecipeHandlers，直接从 recipe 提取（L670 注释已说明）。

**优先级**：P3（已有 workaround）

### [P3-2] 反射调用未统一异常处理

**位置**：多个 RecipeHandler

**观察**：
各个 handler 的反射调用异常处理不统一：
- 部分返回 `null`
- 部分返回空列表
- 部分记录日志，部分静默失败

**建议**：
在 `AbstractRecipeHandler` 中提供统一的反射工具方法，标准化异常处理策略。

**优先级**：P3（不影响功能，仅维护性）

---

## 安全检查清单

| 检查项 | 状态 | 备注 |
|--------|------|------|
| 空配方处理 | ✅ | 均有检查 |
| 空 ingredient 过滤 | ✅ | 均有过滤 |
| Count 计算正确性 | ✅ | 无明显错误 |
| 反射异常捕获 | ✅ | 均有 try-catch |
| 缓存策略正确性 | ⚠️ | 按类缓存有边界问题（P3-1） |

---

## 总结

**代码质量**：良好，配方处理器职责单一  
**关键风险**：无高优先级风险  
**优化空间**：缓存策略可优化为按实例，反射异常处理可统一  

**修复优先级**：所有发现均为 P3，不阻塞发布。
