# mods 存储/杂项域静态审计 — aetherworks / sophisticatedbackpacks / avaritia / touhoulittlemaid / aether / confluence / 其余小 mod

来源：workflow 抢救出结构化候选，随后由主会话**逐条对照真代码复核**（读了 `AbstractBatchDelegate`、`EidolonBatchDelegate` 正确范式、两个 aetherworks delegate 的完整放料/清理路径）。两条 P0 均确认成立，非误报。

一句话总评：aetherworks 的两个 batch delegate 在失败/超时清理路径 `clearMachineState` 里**无条件把机器物理槽的物品重新 insert 回 RS 网络**，缺基类契约要求的 `usingSharedLedger` 双退守卫；由于两者均为共享账本独占（`tryStartSingleCraft` 恒返回 false），链 abort 时账本已退款一次、清理又退一次，构成**每次失败都触发的物品复制**。这是本轮目前发现的最高危问题。

---

### [P0] AetherworksBatchDelegate.clearMachineState 缺 usingSharedLedger 守卫，abort/超时时 anvil input 双退刷物
> ✅ **已于 2026-07-23 在工作区修复**：`clearMachineState` 加 `if (!usingSharedLedger)` 守卫，并新增 `refundToRSNetwork(stack, player)` helper 接住 insertItem 余量（对齐 AetherFurnace 正确范式）；已过 `./gradlew compileJava`。以下为修复前的发现存档。
- 文件: mods/aetherworks/AetherworksBatchDelegate.java:344
- 维度: 资源守恒
- 现象: `clearMachineState`（:339-348）从 anvil inventory slot 0 `extractItem`，然后 `if (!leftover.isEmpty() && network != null) network.insertItem(leftover, leftover.getCount(), Action.PERFORM)`，无任何 `usingSharedLedger` 条件。
- 风险: 该 delegate `tryStartSingleCraft` 返回 false（:171-173），`tryStartWithMaterials` 恒置 `usingSharedLedger=true`（:179），即**永远走共享账本**。链 abort 时 `AbstractBatchDelegate.onBatchFailed`（final，:184）先由链的 `ExtractionLedger.refundCommitted` 把预留材料退回 RS，随后调用 `clearMachineState` 又把物理放进 anvil 的那份 input（`tryStartWithMaterials` 在 :202-204 用 `materials.get(0).copy()` 放入 slot 0）提取出来再 insert 一遍 → **同一份材料退两次**，玩家每次 anvil 锻造失败/超时都净赚一份输入材料。
- 证据: 基类 javadoc（AbstractBatchDelegate.java:177-181）明确："The `clearMachineState` implementations already guard against double-refund via `usingSharedLedger` / `refundToRS` checks." 正确范式见 `EidolonBatchDelegate.refundAll()`（:1133-1137）：`ExtractionLedger activeLedger = usingSharedLedger && sharedLedger != null ? sharedLedger : ledger; if (activeLedger == null || !activeLedger.isCommitted()) return; activeLedger.refundCommitted(network, player);` —— 只走账本、幂等、物理槽只清不再 insert RS。aetherworks 完全缺此守卫。

### [P0] AetherworksToolStationBatchDelegate.clearMachineState 循环 slot 0-5 逐槽重复退款
> ✅ **已于 2026-07-23 在工作区修复**：同 anvil，6 槽循环内加 `if (!usingSharedLedger)` 守卫并统一走新增的 `refundToRSNetwork(stack, player)`；已过 `./gradlew compileJava`。以下为修复前的发现存档。
- 文件: mods/aetherworks/AetherworksToolStationBatchDelegate.java:365
- 维度: 资源守恒
- 现象: `clearMachineState`（:359-370）`for (int i = 0; i < 6; i++)` 逐槽 `extractItem(i, 64, false)`，非空则 `network.insertItem(leftover, ...)`，同样无 `usingSharedLedger` 守卫。
- 风险: 与 anvil 同源同型，且更严重——tool station 一次放多个材料到连续槽（`tryStartWithMaterials` :190-200 循环 `setStackInSlot(i, input)`），abort 时账本退一遍、这里 6 个槽再各退一遍，**多份材料同时被复制**。同样恒共享账本（:164-166 `tryStartSingleCraft` 返回 false，:172 恒置 true）。
- 证据: 同上基类契约（AbstractBatchDelegate.java:177-181）与 Eidolon 正确范式。

---

## 修复方向（记录，暂不改）
两个 delegate 的 `clearMachineState` 应仅清空物理槽（`setStackInSlot(i, EMPTY)` / `extractItem` 丢弃），**不再 `network.insertItem`**——材料退款由共享账本的 `refundCommitted` 统一负责。或最低限度加 `if (!usingSharedLedger)` 守卫包住 insert。属历史刷物家族 [[bug_delegate_output_extraction]] / [[bug_secondary_outputs_dup]] 同类。

## 待补审
本域还含 sophisticatedbackpacks / avaritia / touhoulittlemaid / aether / confluence / tacz / slashblade / rs / majruszsaccessories / jei 等小 mod delegate，尚未逐文件通读，仅确认了 aetherworks 两处。后续主会话补齐。
