# 审查域：crafting-plan-tree（合成计划 UI + 配方树）

范围：`crafting/plan/` (9 文件) + `crafting/tree/` (8 文件)，约 5600 行
审查方式：静态代码审查，重点 (1) 服务器→客户端 PlanResponsePacket 解码健壮性（崩溃/OOM 面）；(2) 客户端图消费的越界/递归安全；(3) 侧隔离（客户端类不在专用服务器加载）；(4) 未提交 diff 的回归风险。
结论：**0 P0 / 0 P1**；3 P3。本域为客户端 UI 主导，无资源守恒逻辑（不产刷物/丢物）。PlanResponsePacket 解码器防御非常扎实。

---

## 关键正确性验证（已确认安全，仅记录）

### PlanResponsePacket 解码器全面加固
`plan/PlanResponsePacket.java`：
- 所有集合/数组长度经 `readBoundedCount`（≤4096，L41-47）限制，防止伪造 count 触发 OOM 预分配。
- 所有计数字段经 `readNonNegativeVarInt`（L49-53）拒绝负值。
- 跨字段校验：`unresolvedQuantity > quantity` 抛 DecoderException（L406-407）；requestId 范围校验（L284-286）。
- 尾字节检查：`readableBytes() != 0` → 抛异常，fail-closed（L289-291）。
- 枚举 ordinal 安全：`OutputView.kind()`（PlanGraphView.java:140-144）对 `kindOrdinal` 做 `>=0 && <values.length` 边界检查，越界回退 PRIMARY，不会 `ArrayIndexOutOfBounds`。`roleOrdinal` 为 write-only（全仓无 `.roleOrdinal()` 读取点），无 enum-index 崩溃向量。

### 客户端图消费者越界/递归安全（PlanTreeModel）
`tree/PlanTreeModel.java` 用服务器权威 DAG 视图重建配方树：
- 节点引用经 `HashMap<Integer,NodeView>`（L84-85）**map 查找**而非数组下标；`nodes.get(producerNodeId)` 返回 null 时（L149-155）建 `markCycle()` 断裂节点，不崩。
- 递归用 `Set<Integer> path` + `path.add(nodeId)`（L156-161）做环检测，恶意自引用/成环图不会栈溢出。
- 解码器允许的任意 nodeId（0..4096）在消费侧都被安全处理。

### 侧隔离正确
`PlanResponsePacket.handle` 在 `ctx.enqueueWork` 内经 `localizePlanForClient`（标注 `@OnlyIn(Dist.CLIENT)`，L469）执行；解码本身不触碰客户端专用类。

---

## 发现清单

### [P3] CraftingPlanScreen.selectAlternative 单点 preferenceKey 缺 null 守卫（与同文件其余三处不一致） ✅ 已修复

**修复时间**：2026-07-24

- 文件：`crafting/plan/CraftingPlanScreen.java:1221-1226`
- 原现象：`forced.remove(CraftingResolver.preferenceKey(treeKey.stack(1)).toString())` 直接对 `preferenceKey(...)` 返回值调 `.toString()`。`CraftingResolver.preferenceKey`（CraftingResolver.java:45-52）在 `ForgeRegistries.ITEMS.getKey` 返回 null 时会返回 null → 此处 NPE。
- 修复：添加 null 守卫，与同文件其他三处对齐：
  ```java
  ResourceLocation pk = CraftingResolver.preferenceKey(treeKey.stack(1));
  if (pk != null) forced.remove(pk.toString());
  ```

### [P3] PlanGraphView 协议版本号解码后未校验 ✅ 已修复

**修复时间**：2026-07-24

- 文件：`crafting/plan/PlanResponsePacket.java:363-368`
- 原现象：`version` 字段被解码并存入 `PlanGraphView`，但客户端消费路径未对其做兼容性断言。若未来服务端 bump 图协议版本而客户端旧，可能按错误布局解读后续字节。
- 修复：添加版本校验，不兼容时抛出 DecoderException：
  ```java
  int version = buf.readVarInt();
  if (version != CraftPlanGraph.CURRENT_VERSION) {
      throw new DecoderException("Unsupported graph protocol version: " + version
              + ", expected: " + CraftPlanGraph.CURRENT_VERSION);
  }
  ```

### [P3] RecipePreviewRenderer 合成宝石切割图标查找每次都走 ForgeRegistries（未缓存） ✅ 已修复

**修复时间**：2026-07-24

- 文件：`crafting/tree/RecipePreviewRenderer.java:286-293`
- 原现象：`isSyntheticGemCuttingRecipe` 分支每帧渲染都 `ForgeRegistries.ITEMS.getValue(new ResourceLocation("apotheosis","gem_cutting_table"))`，而同文件其余类别图标走 `iconCache.computeIfAbsent`。属渲染热路径上的可缓存查找。
- 修复：添加缓存字段 `gemCuttingTableItem`，lazy init，避免重复查找：
  ```java
  private net.minecraft.world.item.Item gemCuttingTableItem;
  
  if (gemCuttingTableItem == null) {
      gemCuttingTableItem = ForgeRegistries.ITEMS
              .getValue(new ResourceLocation("apotheosis", "gem_cutting_table"));
  }
  ```

---

## 覆盖说明
- 未提交 diff 复核：SelectedPath（导出改为仅 `explicitSelections` 用户显式选择，不含 server 已知的自动默认——**语义修复，非 bug**）、CraftingPlanScreen（选择键从裸 item id 改为 NBT 感知的 `CraftingResolver.preferenceKey`，去掉冗余 `else forced.put`——因 `exportForcedSelections` 已覆盖，**行为等价重构**）、RecipePreviewRenderer（宝石切割图标——见上 P3）。均无守恒/崩溃回归。
- 其余纯渲染/布局类（PlanRenderEngine 368、PlanTreeRenderer 341、PlanTreeLayout、PlanAnimationController、PlanGraphView、PlanStep、PlanWarnings、IngredientKey、JeiSubtreeBuilder、PlanPreviewClient、PlanResponse）：客户端展示逻辑，无服务端状态变更、无守恒风险；未发现崩溃向量。
