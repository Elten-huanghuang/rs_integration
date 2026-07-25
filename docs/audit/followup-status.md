# 后续审计状态（更新于 2026-07-24）

## Aetherworks 双重退款

已复核工作区当前代码，原审计结论已过时：

- `AetherworksBatchDelegate.clearMachineState` 在共享账本路径下受 `!usingSharedLedger` 守卫保护。
- `AetherworksToolStationBatchDelegate.clearMachineState` 同样受守卫保护。
- 两者都通过 `refundToRSNetwork` 接住网络插入余量，无法插入的物品继续走玩家掉落兜底。

这两项不再是未修复问题，且 `compileJava` 已通过。

## Ars Nouveau 配方覆盖

早期审计结论已经过时。当前仓库已实现并注册：

- `ArsImbuementBatchDelegate`：支持 `ars_nouveau:imbuement` 配方及
  `ars_nouveau:imbuement_chamber` 方块绑定；
- `ArsApparatusBatchDelegate`：支持 `ars_nouveau:enchanting_apparatus` 配方及同名方块绑定；
- `ArsNouveauRecipeHandler`、JEI 分类和反射探针。

2026-07-24 已修复绑定表误用 `ImbuementTile`/`EnchantingApparatusTile` 的问题：绑定器匹配的是
方块类，现改为 `ImbuementBlock`/`EnchantingApparatusBlock`，并用稳定注册 ID 作为回退。
其余 Ars Nouveau 配方类型仍按随机产物、NBT 变换、实体产物或世界副作用明确排除。

## Delegate 与异常生命周期审计

当前已修复 Ferment、TLM、Malum 等已知路径，但尚未完成所有 delegate 的逐类验证。
待完成范围包括：

- `clearMachineState`、`onBatchFailed`、`onBatchFinished` 的恰好一次语义；
- 机器拆除、区块卸载、玩家下线和超时期间的退款/结算顺序；
- shared-ledger 与 private-ledger 两条路径是否存在重复退款；
- 外部抽取机器槽时，是否会同时退款并交付产物。

在这些路径完成逐类证据核对前，不应宣称所有 delegate 生命周期已经审计完成。
