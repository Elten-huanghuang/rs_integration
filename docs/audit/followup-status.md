# 后续审计状态（2026-07-23）

## Aetherworks 双重退款

已复核工作区当前代码，原审计结论已过时：

- `AetherworksBatchDelegate.clearMachineState` 在共享账本路径下受 `!usingSharedLedger` 守卫保护。
- `AetherworksToolStationBatchDelegate.clearMachineState` 同样受守卫保护。
- 两者都通过 `refundToRSNetwork` 接住网络插入余量，无法插入的物品继续走玩家掉落兜底。

这两项不再是未修复问题，且 `compileJava` 已通过。

## Ars Nouveau 配方覆盖

`docs/ARS_NOUVEAU_RECURSIVE_CRAFTING_PLAN.md` 是设计与反编译核对计划，不是已完成实现报告。
当前仓库没有 Ars Nouveau delegate/recipe handler 实现；因此：

- “约 15 类中仅 2 类支持”是计划中的自动化裁定，不代表已经实现了这 2 类。
- 其余类型已经按随机产物、NBT 变换、实体产物或世界副作用明确排除，不应当为了追求覆盖率强行接入。
- 在实际 delegate 与 handler 落地前，Ars Nouveau 应标记为“计划完成、实现未开始”，不能标记 supported。

## Delegate 与异常生命周期审计

当前已修复 Ferment、TLM、Malum 等已知路径，但尚未完成所有 delegate 的逐类验证。
待完成范围包括：

- `clearMachineState`、`onBatchFailed`、`onBatchFinished` 的恰好一次语义；
- 机器拆除、区块卸载、玩家下线和超时期间的退款/结算顺序；
- shared-ledger 与 private-ledger 两条路径是否存在重复退款；
- 外部抽取机器槽时，是否会同时退款并交付产物。

在这些路径完成逐类证据核对前，不应宣称所有 delegate 生命周期已经审计完成。
