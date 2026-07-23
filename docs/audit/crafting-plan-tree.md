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

### [P3] CraftingPlanScreen.selectAlternative 单点 preferenceKey 缺 null 守卫（与同文件其余三处不一致）
- 文件：`crafting/plan/CraftingPlanScreen.java:1211-1212`
- 现象：`forced.remove(CraftingResolver.preferenceKey(treeKey.stack(1)).toString())` 直接对 `preferenceKey(...)` 返回值调 `.toString()`。`CraftingResolver.preferenceKey`（CraftingResolver.java:45-52）在 `ForgeRegistries.ITEMS.getKey` 返回 null 时会返回 null → 此处 NPE。
- 对比：同文件 `exportForcedSelections`（L503）、`selectTreeBranch`（L1707-1709）、`selectionKey`（L1239-1243）三处都做了 null 检查。仅此一处遗漏。
- 可达性：`treeKey` 源自 `findTreeKey`，其 `node.displayStack` 的 `selectionKey` 已非空才匹配成功，故实际触发需要「未注册物品但恰好前面算出过非空 key」的边界，可达性低 → P3。
- 修复方向：与其余三处对齐，先取 `ResourceLocation pk = CraftingResolver.preferenceKey(treeKey.stack(1)); if (pk != null) forced.remove(pk.toString());`。

### [P3] PlanGraphView 协议版本号解码后未校验
- 文件：`crafting/plan/PlanResponsePacket.java:364`（`readGraph` 读 `version` 后从不比对）+ `PlanGraphView.java:32`
- 现象：`version` 字段被解码并存入 `PlanGraphView`，但客户端消费路径未对其做兼容性断言。若未来服务端 bump 图协议版本而客户端旧，可能按错误布局解读后续字节（尽管 bounded-count + 尾字节检查会兜住多数畸形，但可能产出语义错误的树而非报错）。
- 修复方向：`readGraph` 内加 `if (version != PlanGraphView.CURRENT_VERSION) throw new DecoderException(...)`，或至少 debug 日志 + 优雅降级为无图视图。当前 mod 内版本同步发布，风险低 → P3。

### [P3] RecipePreviewRenderer 合成宝石切割图标查找每次都走 ForgeRegistries（未缓存）
- 文件：`crafting/tree/RecipePreviewRenderer.java:286-291`（未提交 diff 新增）
- 现象：`isSyntheticGemCuttingRecipe` 分支每帧渲染都 `ForgeRegistries.ITEMS.getValue(new ResourceLocation("apotheosis","gem_cutting_table"))`，而同文件其余类别图标走 `iconCache.computeIfAbsent`。属渲染热路径上的可缓存查找。
- 影响：仅 apotheosis 宝石切割合成的树卡片，且 ForgeRegistries 查找本身廉价 → 纯性能微优化，P3。
- 修复方向：把该 `Item` 解析结果缓存进一个静态/成员字段（lazy init），或纳入现有 `iconCache` 机制。

---

## 覆盖说明
- 未提交 diff 复核：SelectedPath（导出改为仅 `explicitSelections` 用户显式选择，不含 server 已知的自动默认——**语义修复，非 bug**）、CraftingPlanScreen（选择键从裸 item id 改为 NBT 感知的 `CraftingResolver.preferenceKey`，去掉冗余 `else forced.put`——因 `exportForcedSelections` 已覆盖，**行为等价重构**）、RecipePreviewRenderer（宝石切割图标——见上 P3）。均无守恒/崩溃回归。
- 其余纯渲染/布局类（PlanRenderEngine 368、PlanTreeRenderer 341、PlanTreeLayout、PlanAnimationController、PlanGraphView、PlanStep、PlanWarnings、IngredientKey、JeiSubtreeBuilder、PlanPreviewClient、PlanResponse）：客户端展示逻辑，无服务端状态变更、无守恒风险；未发现崩溃向量。
