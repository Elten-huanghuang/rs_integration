# RS-Integration 代码审查报告

> 审查日期：2026-07-11
> 版本基线：`8a4f736`（v1.1.2 审计修复后）
> 范围：`src/main/java`（338 个文件，约 69,227 行）
> 覆盖：**全模块深度审查已完成**（7 个模块集群，逐文件）

---

## 0. 审查覆盖范围

| 模块集群 | 审查方式 | 状态 |
|------|----------|------|
| `resonance/*`（共振背包 / 被动效果） | 逐文件 | ✅ |
| `network` + `sidepanel` + `autoeat`（网络包 / GUI） | 逐文件 | ✅ |
| `crafting/*`（含 batch / loadbalancer / plan / tree） | 逐文件 | ✅ |
| `mixin/*`（全部注入点） | 逐文件 | ✅ |
| `reflection/*`（contract / probes） | 逐文件 | ✅ |
| `mods/*` 前半（15 个 mod 适配器 + recipe handlers） | 逐文件 | ✅ |
| `mods/*` 后半 + `machine` / `transfer` / `config` / `command` / `util` / `api` | 逐文件 | ✅ |

全库反射用法（200+ 处）已针对 SRG 安全逐一核对。

---

## 1. 缺陷清单

### Critical（阻断编译）

**C-1　`resonance/backpack/ResonanceDiskInventory.java:118` 缺失 `import java.util.ArrayList`**
`clearContent()` 使用 `new ArrayList<>(disk.delegate().getStacks())`，但未 import（import 段止于第 8 行）。该文件无法编译；若 mod 能运行说明跑的是 stale class。

---

### High（高危：丢物 / 刷物 / 专服故障）

**H-1　`resonance/backpack/ResonanceDiskInventory.removeItem`（81-89 行）刷物向量**
只改本地 `slots[]`，依赖"注释假设 `ResonanceSlot.setChanged()` 总被调用"。任何直接调 `Container.removeItem` 的路径（漏斗、其他 mod 自动化、部分菜单分支）从 UI 移除但不从后端 RS 磁盘抽取 → 下次 `loadFromDisk` 时物品复活（刷物）。同类 `removeItemNoUpdate`（92 行）正确调了 `disk.manualExtract`，两个 override 不一致。

**H-2　`resonance/item/ResonanceDiskItem.inventoryTick`（35 行）初始化守卫用错**
守卫写成 `stack.hasTag()`，本意"已有 Id 就跳过"。任何无关 tag（改名 / 其他 mod / RSISlot 残留）都会让首 tick 即 true → 磁盘永不注册进 `StorageDiskManager` → 表现为无后端存储的磁盘，存入物品消失。应针对具体 Id tag 判断。

**H-3　`sidepanel/network/OpenBoundMachineGuiPacket.java:244` 反射原版字段 `litTime`（SRG）**
`AbstractFurnaceBlockEntity.class.getDeclaredField("litTime")`——`litTime` 运行时被 SRG 重映射为 `f_58315_`，生产环境抛 `NoSuchFieldException`。被 try/catch 吞掉降级 debug，故不硬崩，但"炉子是否已点燃"检查恒失败 → 即使已点燃仍从 RS 反复抽燃料。修复：AT 或 Mixin `@Shadow`。

**H-4　`mods/avaritia/CraftingTableBatchDelegate.java:135-137` 清空网格销毁玩家物品**
```java
for (int i = 0; i < handler.getSlots(); i++)
    handler.extractItem(i, 64, false);   // 返回的 stack 被丢弃
```
放料前清空每个网格槽，抽出的内容既不退回 RS 也不还给玩家。若玩家手动在 TierCraftTile 网格里留了物品，将被永久销毁。应退还或在非空时中止。（末尾的 `clearGrid` 是合规的——移除刚消耗的输入。）

**H-5　`mods/malum/MalumRunicWorkbenchBatchDelegate.java`（约 133-213 行）次要输入泄漏**
`tryStartSingleCraft` / `tryStartWithMaterials` 要求 `specs.size() >= 2` 并对两个 spec 都 reserve+commit（从 RS 物理抽取），但随后只 place/consume `reserved.get(0)`。第二个输入被抽出 RS 却从未放入机器、也从未退还 → 每次此类合成永久吞掉次要输入物。

**H-6　`machine/MachineHub.java:292` + `mixin/.../GridScreenMachineTabMixin.java:52` 客户端直读 SERVER 配置**
`shouldUseHub()` 直接 `RSIntegrationConfig.MACHINE_TAB_THRESHOLD.get()`，该项在 `RSIntegrationConfig.java:470` 定义为 SERVER 侧。专用服务器上客户端没有 SERVER 配置，`.get()` 会异常或读默认值。项目已建同步管道 `ConfigSyncPacket → ClientSyncedConfig`（`ClientSyncedConfig.java:21-22` 写入），但**从无读取方**（`isSynced()` 无调用者），消费方全部绕过它。→ 专服上 Machine Hub / Tab 阈值行为错误。

---

### Medium（中危）

**M-1　`crafting/StepExecutor.planRecipeIngredients`（193、228 行）批量步数守卫语义错误**
守卫 `ctx.steps.size() + batches > maxSteps()` 用"操作次数 `batches`"比"步骤节点预算 `maxSteps`"（默认 4096），而这两个方法只规划材料、不新增 step（step 由 `craftBatched` 用 `+1` 添加）。→ 大批量合成（batches>4096 或叠加后越界）被错误拒绝。功能性过度拒绝，不损坏物品。

**M-2　客户端解码无上限读取（恶意服务器 DoS）**
`autoeat/network/BlacklistSyncPacket.java:25`、`sidepanel/RSSidePanelSyncPacket.java:85`、`RSSidePanelDeltaPacket.java:82`、`sidepanel/network/RSBindingSyncPacket.java:36`、`MachineStatusDeltaPacket.java:40`：`readVarInt()` 后无上限循环分配。对比 `UpdateBlacklistPacket.readSet`（47 行）已 clamp 4096。均 PLAY_TO_CLIENT，仅恶意/异常服务器可致客户端 OOM。

**M-3　`resonance/disk/ResonanceDiskFactory` 构造期急切访问 RS API（加载顺序脆弱）**
构造函数调 `API.instance().getStorageDiskRegistry().get(...)`；若 RS 未完成注册即被触及，返回 null，后续 `createFromNbt/create` NPE，无 null 检查。

**M-4　执行路径整数溢出绕过 `mulCount` 钳制**
`crafting/AsyncCraftChain.java:371、374、377、387` 与 `crafting/CraftPacketUtils.java:207、210、217、230、265、272` 用裸 int 相乘（`getCount() * executions` 等），未复用 `mulCount` 的 long 提升+钳制。executions 极大时溢出成负数，virtualInventory 计数错乱。由 `REPEAT_COUNT_MAX` 上限缓解。

**M-5　YHK 多个 delegate `usingSharedLedger` 翻转拷贝 bug**
`mods/youkaishomecoming/` 下 `MokaPotBatchDelegate`、`SteamerBatchDelegate`、`KettleBatchDelegate:142`、`FermentationTankBatchDelegate:154`、`CookingPotBatchDelegate`（124 设 false，140 无条件设回 true）。各自 `clearAndRefund()` 以 `!usingSharedLedger` 为门槛。自持账本的单次合成"启动成功后异步失败"时，已 commit 物品不会退还 → 丢物。CuisineBoard 不受影响（防重逻辑独立）。

**M-6　`machine/MachineStatusReader.java:25-26` 反射原版方法 `isLit` 无 SRG 兜底**
`Reflect.findMethod(AbstractFurnaceBlockEntity.class, "isLit", ...)` 只给 MCP 名；同文件字段（23-24 行）正确带了 SRG 兜底（`f_18769_`/`f_18768_`），唯独此方法漏了。→ 运行时 `IS_LIT` 为 null，机器 WORKING 被误判 IDLE（不崩，降级）。违反记忆「别反射原版方法」。

**M-7　`mixin/.../ContainerDistanceMixin.java:20` 客户端 stillValid 全量绕过**
`stillValid` HEAD inject 在 `player.level().isClientSide` 时无条件返回 `true`，绕过了**所有**容器的距离检查（不止远程 GUI）。本意应基于 `hasActiveAuthorizationForBlock` 门控，实际范围过宽，可让任意容器超距保持打开。

**M-8　`mixin/yuusha/NineSwordBooksMixin.java:66` `@Redirect List.size()` 全量重定向**
`@Redirect` 命中方法内**每个** `List.size()` 调用点，仅靠 `list.get(0) instanceof Integer`（69 行）防御。若这些方法里有其他 `List<Integer>` 用途会被静默误算，随 Malum 内部实现变化而脆弱。

---

### Low（低危 / 代码异味）

- **L-1** `crafting/AsyncCraftManager`：在 `CopyOnWriteArrayList` 上 `synchronized`（74 行），其余方法不加锁——语义误导，非竞态（均在服务器主线程，COW 单操作安全）。
- **L-2** `crafting/StepExecutor.mulCount` 溢出钳制到 `Integer.MAX_VALUE` 并继续参与后续计数，可能生成不合理计划量；仅告警。
- **L-3** `resonance/backpack/ResonanceBackpackContainer.getStoredCount`（107-113 行）客户端返回"非空槽位数"（≤36），服务端返回真实件数（≤2304），表头/tooltip 显示不一致。
- **L-4** `resonance/backpack/ResonanceDiskInventory.setItem`（102-104 行）只写 `slots[]` 不同步磁盘，与 H-1 叠加放大 UI/后端不同步。
- **L-5** `crafting/loadbalancer/LoadBalancer.dispatch` javadoc（67-68 行）提到的 `outputPerCraft`/`level` 参数与实际签名不符（文档腐烂）。
- **L-6** `mods/vanilla/VanillaMachineBatchDelegate.java:389-392` 反射原版字段 `litTime` 无 SRG 兜底 → SRG 下读作 0，轻微超供燃料（营火字段有正确兜底）。
- **L-7** `mods/majruszsaccessories/MajAccessoryCompressor.java:95-108` rollback 未撤销已成功插入的 result，边缘条件下可能既留 result 又插回输入 = 重复。
- **L-8** `mods/rs/RSGridSearchCache.java:93` `mc.level.getGameTime()`：切维度/断连瞬间 `mc.level` 可能为 null（守卫仅查 `mc.player`）；有 try-catch 兜底，影响小。
- **L-9** `crafting/ResolutionContext.java:196` 排序键 `getKey(k.item()).toString()`，item 未注册返回 null 时 NPE（边缘）。
- **L-10** `crafting/PreviewRateLimiter` 缓存仅登出时清理，异常断开会累积（无界增长，影响很小）。
- **L-11** `reflection/contract/ContractValidation.java:108-109` `MethodContract` 校验始终用 `getDeclaredMethod`，无 VANILLA-origin 分支（字段有）；当前无 vanilla MethodContract 实例，是潜在陷阱非现行 bug。
- **L-12** `mixin/yuusha/YuushaNineSwordBooksMixin.java:29-36` 日志 bug：`setReturnValue(total)` 后再用 `getReturnValue()-extra` 计算 hotbar，clamp 触发时日志值错（仅日志）。
- **L-13** `mixin/.../UpgradeHandlerMixin.java:18-22` 空 no-op `@Inject`（RETURN, 不可取消），死注入点，应删。

---

## 2. 已核对确认「正确」的项（非误报）

- **SRG 反射纪律**：除 H-3 / M-6 / L-6 三处遗漏兜底外，全库反射基本正确——大量指向第三方类（安全）；对第三方类上的原版继承方法用 `remap=false` + SRG 名（`m_6375_` 等）系统性一致，生产 jar 正确（仅 dev 环境需 SRG 名测试）。`RecipeGuiLayoutsMixin:563-564` 对 `Recipe.getId()` 用 dev + SRG（`m_6423_`）双名兜底——正确。`player.getInventory().setChanged()` 等均为直接 cast 调用。
- **排序契约**（记忆 `bug_server_side_panel_sort`）：`DisplayListManager.resort` + `SortMode` 已满足传递性，服务端按缓存顺序发送、排序交客户端，旧 bug 无法复现。
- **网络包物品安全**：`RSSidePanelClickPacket`/`RSInventoryTransferPacket` 先 SIMULATE 再 PERFORM、clamp 数量、校验权限、所有返回路径（含异常）都 `syncCursorSlot`；服务端 handler 均 null 校验 sender、拒 FakePlayer、走 `enqueueWork`。
- **合成物品原子性**：`ExtractionLedger` 三阶段（preCheck→批量 extract→confirm），部分失败 `rollbackExtractedPhases` 退回来源；`executeCraftingSteps` 用 try-with-resources 持 ledger，early-return 自动回滚；余料走 Forge API `getRecipeRemainders` 而非反射（避免桶类 dupe）。
- **第一批适配器**：FarmersDelight CookingPot（跳过槽 6 meal display）、CrabTrap 槽界、CrockPot 共享账本 `!usingSharedLedger` 防重复退款——均正确。
- **`reflection/*`**：17 个 probe 全部 class-name-only 契约 + 优雅降级；`ContractValidation` 字段走 `ObfuscationReflectionHelper`（SRG 安全）。`Reflect.java` 负结果缓存、层级遍历、调用方 null 检查——扎实。

---

## 3. 多维度打分

10 = 优秀，5 = 及格。

| 维度 | 分数 | 说明 |
|------|:----:|------|
| 网络安全 | 8.5 | simulate-before-perform、权限校验、光标重同步、限流、FakePlayer 防护到位；扣分于无上限解码（M-2）。 |
| 物品安全 / 防刷 | 6.5 | 合成核心账本原子性优秀；但存在多个丢物/刷物向量：共振 `removeItem`（H-1）、Avaritia 清网格（H-4）、Malum 次要输入（H-5）、YHK 账本翻转（M-5）。 |
| 兼容性 / 反射健壮性 | 7.5 | 反射纪律整体好、第三方 SRG 规避意识清晰；但 3 处原版反射漏 SRG 兜底（H-3/M-6/L-6）成模式。 |
| 并发安全 | 7.5 | 状态变更基本收敛到 server tick / `enqueueWork`；仅 COW+synchronized 混用等异味（L-1）。 |
| 正确性 | 7.0 | 核心稳健；批量步数守卫（M-1）误拒大批量、执行路径裸乘溢出（M-4）、Mixin 两处过宽注入（M-7/M-8）。 |
| 配置管理 | 5.5 | 客户端/服务端配置边界处理有真实故障：同步管道建了没接，消费方直读 SERVER 配置（H-6），专服上行为错误。 |
| 可维护性 | 6.5 | 防御式风格、注释充分；但依赖未文档化不变式（H-1）、反射分散、死代码/日志 bug/文档腐烂。 |
| 编译健全性 | 4.0 | 存在阻断编译的缺失 import（C-1），拉低整体。 |

### 分模块评分

| 模块 | 分数 | 一句话 |
|------|:----:|--------|
| `reflection/*` | **9 / 10** | 契约化设计干净，SRG 字段处理正确，急切校验 + 优雅降级。 |
| 网络 / GUI（network / sidepanel / autoeat） | **8 / 10** | 成熟、安全意识强，远超一般 mod 集成水平。 |
| 合成引擎（crafting，含子模块） | **8 / 10** | 三阶段账本 + 自动回滚 + 跨维度强加载扎实，扣分于溢出一致性缺口与批次守卫。 |
| mods 适配器（前半 15 个） | **8 / 10** | 账本纪律统一、null/SRG 处理到位，唯 Avaritia 清网格丢物。 |
| mods 适配器 + core（后半） | **7.5 / 10** | 工程质量高，扣分集中在配置同步没接 + YHK 账本翻转拷贝 + 2 处原版反射漏兜底。 |
| `mixin/*` | **7 / 10** | 注入点大体正确且防御式，但两处过宽注入（List.size / stillValid）+ 死代码。 |
| 共振背包（resonance） | **6 / 10** | 设计巧妙，但带 1 个编译阻断 + 2 个刷物/丢物向量。 |

> **综合（加权）：约 7.3 / 10。**

---

## 4. 修复优先级建议

1. **立即（阻断 / 丢物）**：C-1（补 import）、H-1（`removeItem` 补 `manualExtract`）、H-2（改具体 Id tag 判断）、H-4（Avaritia 清网格改退还/中止）、H-5（Malum 放入或退还第二输入）。
2. **本迭代（专服故障 / SRG）**：H-6（接通 `ClientSyncedConfig` 读取路径）、H-3 / M-6 / L-6（原版反射统一改 AT/Shadow 或补 SRG 兜底）、M-1（守卫改 `+1`）、M-5（YHK `usingSharedLedger` 翻转修正）。
3. **有空即可**：M-2（解码加上限）、M-3（RS API 加 null 守卫 + 延迟初始化）、M-4（执行路径复用 `mulCount`）、M-7 / M-8（收窄 Mixin 注入范围）、L 系列（死代码、日志、文档、边缘 NPE）。

---

*本报告由多 agent 并行审查汇总，全模块已逐文件覆盖。所有 C/H/M 级结论均已对照实际代码核实，非静态规则套用。*
