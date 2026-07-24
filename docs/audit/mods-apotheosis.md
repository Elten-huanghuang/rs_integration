# 审查域：mods-apotheosis（Apotheosis 集成 + reforging）

范围：`mods/apotheosis/` 18 文件 + `mods/reforging/` 4 文件（后者见覆盖说明），约 2370 行
审查方式：静态代码审查，重点 (1) 三个物品移动面——fletching/gem-cutting batch delegate、library book import 的守恒；(2) gem-cutting 放 2 颗宝石是否等量预留（潜在 dup）；(3) 反射安全；(4) 服务端 library 包越权/并发。
结论：**0 P0 / 0 P1**；2 P3。新增的 gem-cutting 功能与 library import 均守恒正确。

---

## 关键正确性验证（已确认安全，仅记录）

### Gem-cutting 放 2 颗宝石但预留也是 2（无 dup）
`ApotheosisGemCuttingBatchDelegate.start`（L126-129）向菜单 slot 0 和 slot 2 各放一颗 `gems.copyWithCount(1)`（共 2 颗）。对应 `ApotheosisGemCuttingRecipeHandler.getIngredients`（L24-25）返回 `new IngredientSpec(StrictNBTIngredient.of(gem), 2)`——**count=2**。ledger 按 spec 预留 2 颗，放置 2 颗，守恒成立。start L123 还前置校验 `gems.getCount() < 2` 拒绝。无刷物。

### Fletching / Gem-cutting 均为 scratch-menu 模型，账目走 ledger
两个 delegate 都用临时菜单执行原生合成：放入 `material.copyWithCount(...)`（副本）、取结果、`clearMenu()` 清空菜单槽。真实库存账目由 ExtractionLedger 管：
- single-craft 路径（tryStartSingleCraft）建私有 ledger、commit、start 失败即 `refundCommitted`（Fletching L129-133 / Gem L96-99）。
- shared-ledger 路径（tryStartWithMaterials）标 `usingSharedLedger=true`，退款交给 chain。
- `clearMachineState`（Fletching L270 / Gem L166）只 `clearMenu`+`resetState`，**不向网络回插**——与 base `onBatchFailed`（AbstractBatchDelegate L184-202，只做物理清理）配合，无 aetherworks 式双退款（对照 [[bug_aetherworks_double_refund]]）。
- 完成为即时（craftDone 当帧置位），post-craftDone abort 窗口≈0。

### Library book import 是本域最强守恒范例
`ApotheosisLibraryService.importEntries`（L151-219）逐本书 extract-1 → try-insert → 兜底：
- 提取 `extractExactFromNetwork(network, entry.stack(), 1)`（L186），空则 skip。
- 插入前后 `captureState(tile)`（读 getPointsMap/getLevelsMap 快照）；insert 抛异常时用 before≠after 判断**是否已实际提交**（L198-200），避免把「抛异常但已入库」误判为丢失而重复退款。
- remainder 一律走 `refund()`（L207-211）。`refund`（L348-355）：network → 玩家背包 → `player.drop`，**三级 fall-through 从不 void**。
- 并发：`ACTIVE_IMPORTS`（ConcurrentHashMap.newKeySet）按 GlobalPos 加互斥锁（L173），`finally` 释放（L215）；快照 TTL 30s + snapshotId 校验 + knownIds 白名单校验（L157-168）防重放/越权；单请求上限 MAX_IMPORT_PER_REQUEST=64。

### 反射目标均为模组自有类（符合 [[feedback_no_reflect_vanilla_methods]]）
LibraryService 反射的 `BlockEntityMenu.pos/tile`（Placebo）、`EnchLibraryTile.canExtract/extractEnchant/getMax/getPointsMap/getLevelsMap`（Apotheosis）全部是**模组自有类/方法**，非原版，不受 SRG 重映射影响。原版交互（getCapability/getMenuProvider/slot 操作）直调无反射。`adapterReady()`（L375-378）在反射不可用时优雅降级。

---

## 发现清单

### [P3] LibraryService.refund 的 DROPPED 计入 ImportStats 但玩家无明确提示 ✅ 已修复

**修复时间**：2026-07-24

- 文件：`mods/apotheosis/ApotheosisLibraryService.java:348-355` + 语言文件
- 原问题：refund 三级兜底最终掉落到地面时，消息显示 "dropped %s" 没有明确说明掉落在脚下
- 修复：更新语言文件使提示更清晰：
  - 英文：`"dropped at feet %s"`
  - 中文：`"掉落在脚下 %s"`
- 说明：**非守恒问题**（物品确实掉落到世界，未 void），纯 UX 改进。

### [P3] 燃料/宝石台 isTable 等 getKey 结果一致性（getBlockState().getBlock() 的 getKey 判空）
- 文件：`mods/apotheosis/ApotheosisGemCuttingBatchDelegate.java:186-190`（`isTable`）
- 现象：`ForgeRegistries.BLOCKS.getKey(...)` 已判 `id != null`（L189），此处**正确**。记录为对照：Fletching 的 `isFletchingTable`（L313-319）同样判空。本域此项无缺陷，仅作与 distantworlds 域 isStaff 未判空 P3 的横向对照，无需修复。

---

## 覆盖说明
- 未提交 diff 复核：`ApotheosisRSModule.java`（新增 GEM_CUTTING_TYPE 的 ModType.register / configureJei / binding target / recipe handler 注册）——纯**注册接线扩展**，无守恒逻辑，良性。
- 客户端类（client/ApotheosisLibraryClientEvents、ApotheosisLibraryImportScreen）、网络包（network/ApotheosisLibrary* 4 个）：Scan/Import/Level 请求-响应，服务端处理已在 LibraryService.handle 侧确认限流（REQUEST_COOLDOWN_MS=150ms，allowRequest L368-373）+ 距离/维度校验；包本身为数据搬运。
- 数据/纯逻辑类（ApotheosisGemCuttingCatalog、ApotheosisGemCuttingRecipe、ApotheosisFletchingLogic、ApotheosisLibraryModels、ApotheosisLibraryBinding、ApotheosisFletchingRecipeHandler）：无守恒/崩溃向量。
- **reforging 子域**（实际在顶层 `rsintegration/reforging/`，非 `mods/` 下，4 文件）已一并审：`ReforgingRestockRequestPacket.run`（L36-83）把 sigil_of_rebirth 补满 reforging 菜单 slot 2。守恒正确——`takeFromInventory`（L85-97）先扣背包 `fromInventory` 个，网络只再取 `remaining=needed-fromInventory` 个，slot 容量受 `needed ≤ target-current` 约束必能容纳 `inserted`，无丢无刷；`player.containerMenu` 服务端权威，`isSupported` 校验菜单类名防伪造；网络提取有 EXTRACT 权限校验（L63-64）。**唯一小注**：该包无 LibraryService 那样的限流冷却，但单次工作量有界（补满一格即 COMPLETE 返回 0），自限，风险极低（P3 级，不单列）。ReforgingRestock{NetworkHandler,ResultPacket,client} 为注册/数据搬运。
