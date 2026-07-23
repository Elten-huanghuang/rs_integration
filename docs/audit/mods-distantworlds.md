# 审查域：mods-distantworlds（Distant Worlds / Firon Lithum Altar 集成）

范围：`mods/distantworlds/` 19 文件，约 1450 行
审查方式：静态代码审查，重点 (1) 资源守恒——尤其借用型催化剂（dalite_staff）与燃料的提取/归还；(2) 与 aetherworks P0 对照的 clearMachineState 双退款风险；(3) 反射安全（勿反射原版方法）；(4) 服务端包的越权/DoS。
结论：**0 P0 / 0 P1**；3 P3。本域的 `LithumAltarBatchDelegate` 退款设计明显比 aetherworks 严谨（clearPlacedMaterials 只清机器槽、从不回插网络），是模组集成层的正面样板。

---

## 关键正确性验证（已确认安全，仅记录）

### clearMachineState 无双退款（与 aetherworks P0 形成对照）
`LithumAltarBatchDelegate.clearMachineState`（L426-430）只做三件事：`clearPlacedMaterials()`（清空机器/基座槽，从不 `network.insertItem`）、`fuelHelper.refundUnused`、`releaseNetworkStaff`。
- `clearPlacedMaterials`（L331-343）逐个 OwnedSlot：仅当当前槽内容与放入时**完全一致**（same item+tags+count）才 `setSlot(EMPTY)`，然后 `ownedSlots.clear()`。它**不向 RS 网络回插任何物**——虚拟输入的退款完全交给 ledger（`refundCommitted`）。
- 因此「物理清机器 + ledger 退虚拟」正交，不存在 aetherworks 那种「clearMachineState 回插 + ledger 又退」的双计。这正是 [[bug_aetherworks_double_refund]] 应对齐的正确形态。

### 借用型催化剂 dalite_staff 全路径归还
staff 可来自玩家背包/副手（原地耐久损耗）或 RS 网络（`acquireStaff` L187-205 提取 1 个）。
- 网络来源：`staffFromNetwork=true` + `networkStaff` 快照。所有失败早退（tryStartSingleCraft L91/100/106/112/118；tryStartWithMaterials L133/138）都调 `releaseNetworkStaff()`。
- `releaseNetworkStaff`（L250-258）先 `network.insertItem` 回插，**remainder 非空则 `deliver` 给玩家/掉落**（L255）——不丢物。
- 成功路径 `damageStaff`（L229-248）：网络 staff 先 hurtAndBreak 再 releaseNetworkStaff 回插（未碎则整把还网络）；玩家 staff 原地损耗。onBatchFinished（L433-439）再兜底 releaseNetworkStaff。守恒正确。

### 研究绕过 grant/revoke 对称，无永久解锁泄漏
`DistantWorldsResearchBypass`（全文件）：`temporarilyGrant` 只记录**自己 award 成功**的 criteria（L30-33），`Grant.close()`（try-with-resources，L69-74）只 revoke 这些；若授予后仍未 done 立即回滚（L34-37）。invokeNativeStart 用 `try (Grant grant = researchGrant())`（L169）确保退出即撤销。玩家原有成就不受影响。

### 反射未触碰原版方法（符合 [[feedback_no_reflect_vanilla_methods]]）
所有 `getMethod` 反射目标均为 **DistantWorlds 模组自有类**的静态过程（`recipePickerProcedureClass.execute`、`structureIntegrityProcedureClass.execute`——MCreator 生成的 procedure），非原版/Forge 方法，不受 SRG 重映射影响。原版交互（setChanged/sendBlockUpdated/getCapability）全部直调，无反射。

### 服务端状态包充分加固
`LithumAltarStatusRequestPacket.handle`（L38-59）：200ms 限流（per-UUID ConcurrentHashMap）、维度校验、`player.level()==level` 校验、32 格距离校验、chunk load 校验、logout 清理（L61-65）。无越权读取或放大攻击面。

---

## 发现清单

### [P3] LithumAltarFuelHelper.ensureFuel 内部两处 refund 丢弃 remainder（与本域主退款路径不一致）
- 文件：`mods/distantworlds/LithumAltarFuelHelper.java:83, 88`
- 现象：`ensureFuel` 里 `refund(network, extracted)`（L83，插槽失败回退分支）与 `refund(network, remainder)`（L88，部分插入后的余量）都**不接收** `refund()` 的返回值。`refund`（L124-127）本身返回 `network.insertItem` 的 remainder；若 RS 网络此刻已满，这两处的燃料会被静默 void。
- 对比：本域主退款 `refundUnused`（L97-115）正确处理 remainder（回插失败 → 给玩家 → 掉落）。仅 ensureFuel 内联两处遗漏。属同类 [[]] 于 infra-util 域 PlayerUtils P1 的「insertItem 忽略 remainder 丢物」，但触发面窄（仅燃料自动化开启 + 网络满 + 恰在插入失败瞬间），故 P3 而非 P1。
- 修复方向：把 L83/L88 改为捕获返回值并走 `refundUnused` 同款兜底（给玩家/掉落），或抽一个「保证不丢」的 helper 统一三处。

### [P3] 燃料搜索为立方体半径三重循环，半径可配无硬上限
- 文件：`mods/distantworlds/LithumAltarFuelHelper.java:34-45`
- 现象：`findAndLock` 按 `DISTANT_WORLDS_FUEL_SEARCH_RADIUS` 做 `(2r+1)^3` 的 `getBlockEntity` 扫描。若管理员把半径配得很大（如 32 → 27 万次 BE 查询），单次锁定会造成明显卡顿。findAndLock 仅在 start 时调用一次，非每 tick，故非 P1。
- 修复方向：给该 config 加合理上界校验（如 ≤8），或改为只扫描已加载区块的 BE 集合而非逐坐标 getBlockEntity。

### [P3] isStaff 对 getKey 结果未判空
- 文件：`mods/distantworlds/LithumAltarBatchDelegate.java:314-317`
- 现象：`ForgeRegistries.ITEMS.getKey(stack.getItem()).toString()` 未判空。理论上非空 stack 的 item 都已注册，getKey 极难返回 null，但同域其他地方（如 selectFuel L137-142）都做了判空。属健壮性一致性，P3。
- 修复方向：与 selectFuel 对齐，getKey 为 null 时视作非 staff。

---

## 覆盖说明
- 无未提交 diff（本域文件不在 git status 修改列表）。
- 纯数据/客户端类（LithumAltarStatusSnapshot、LithumAltarStatusCache、client/* HUD 与 JEI category、LithumAltarRecipeDefinition/Wrapper/Handler/Resolver、LithumFuelInventoryLogic 纯算术、LithumAltarStructureHelper、LithumAltarStateReader）：无服务端守恒逻辑；LithumFuelInventoryLogic 的 insertionRoom/refundableAddedCount 为纯整数运算，已随 fuelHelper 一并核对语义一致。
- DistantWorldsRSModule / DistantWorldsClientSetup / LithumCoreInteractionHandler：注册与接线，无守恒逻辑。
