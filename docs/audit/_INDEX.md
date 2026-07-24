# RS-Integration 全代码库静态审计 — 总索引

审计标准见 [`_AUDIT_STANDARD.md`](./_AUDIT_STANDARD.md)。纯静态审计：只读代码、只记录，不改源文件。
每域一个 `docs/audit/<domain>.md`。本索引跟踪覆盖进度与发现汇总。

> 背景：19 个包组分域审计。首批 agent 运行完成 3 组并落盘（crafting-core / mixin-rs-minecraft / reflection）后遇网关故障中断；
> 后续 workflow 抢救出 infra-util、mods-storage-misc 两组结构化发现（已逐条对照真代码复核）；余下各组由主会话串行补审。

## 覆盖进度

| 域 | 文档 | 状态 | 发现 (P0/P1/P2/P3) | 来源 |
|---|---|---|---|---|
| crafting-core | [crafting-core.md](./crafting-core.md) | ✅ 已审（3 条 P2 已修复，1 条 P2 复核关闭；当前 0 P2） | 0/0/0/0 | 首批 agent + 复核 |
| crafting-graph | [crafting-graph.md](./crafting-graph.md) | ✅ 已审（publish 日志项已修复） | 0/0/0/1 | 主会话 |
| crafting-batch-lb | [crafting-batch-lb.md](./crafting-batch-lb.md) | ✅ 已审 | 0/0/0/2 | 主会话 |
| crafting-plan-tree | [crafting-plan-tree.md](./crafting-plan-tree.md) | ✅ 已审（3 个 P3 已修复 2026-07-24） | 0/0/0/0 | 主会话 |
| mods-distantworlds | [mods-distantworlds.md](./mods-distantworlds.md) | ✅ 已审（3 条 P3 均已关闭/修复） | 0/0/0/0 | 主会话 |
| mods-apotheosis | [mods-apotheosis.md](./mods-apotheosis.md) | ✅ 已审 | 0/0/0/2 | 主会话 |
| mods-food-cluster | [mods-food-cluster.md](./mods-food-cluster.md) | ✅ 已审（P2 已修复 2026-07-24） | 0/0/0/4 | 主会话 |
| mods-vanilla | [mods-vanilla.md](./mods-vanilla.md) | ✅ 已审（P2 已修复 2026-07-24） | 0/0/0/3 | 主会话 |
| mods-magic-cluster | [mods-magic-cluster.md](./mods-magic-cluster.md) | ✅ 已审 | 0/0/0/5 | 主会话 |
| mods-storage-misc | [mods-storage-misc.md](./mods-storage-misc.md) | ✅ 已审 | 2/0/0/0 | workflow 抢救 + 复核 |
| mixin-rs-minecraft | [mixin-rs-minecraft.md](./mixin-rs-minecraft.md) | ✅ 已审（3 条 P2 和 1 条 P3 已修复/加固） | 0/0/0/8 | 首批 agent + 复核 |
| mixin-mods | [mixin-mods.md](./mixin-mods.md) | ✅ 已审 | 0/0/0/3 | 主会话 |
| sidepanel | [sidepanel.md](./sidepanel.md) | ✅ 已审 | 0/0/0/1 | 主会话 |
| recipe | [recipe.md](./recipe.md) | ✅ 已审 | 0/0/0/2 | 主会话 |
| network | [network.md](./network.md) | ✅ 已审 | 0/0/0/2 | 主会话 |
| reflection | [reflection.md](./reflection.md) | ✅ 已审（3 条 P2 已修复；签名契约 P2 暂缓，不阻塞发布） | 0/0/0/5 | 首批 agent + 复核 |
| infra-util | [infra-util.md](./infra-util.md) | ✅ 已审（P1/P3 均已修复） | 0/0/0/0 | workflow 抢救 + 复核 |
| autoeat-resonance | [autoeat-resonance.md](./autoeat-resonance.md) | ✅ 已审 | 0/0/0/2 | 主会话 |
| villager-compat | [villager-compat.md](./villager-compat.md) | ✅ 已审 | 0/0/0/0 | 主会话 |

进度：19 / 19 域已审。

## P0/P1 汇总（最高优先，需修复）

> ✅ **下列 3 条已于 2026-07-23 在工作区修复**（未提交），修复已过 `./gradlew compileJava`。修复要点见各条尾注。

- **[P0] Aetherworks anvil 双退刷物** — `AetherworksBatchDelegate.java:344` — abort/超时时同料退两遍。详见 [mods-storage-misc.md](./mods-storage-misc.md)。✅ 已修：`clearMachineState` 加 `if (!usingSharedLedger)` 守卫 + `refundToRSNetwork` 接住 insertItem 余量。
- **[P0] Aetherworks tool-station 双退刷物** — `AetherworksToolStationBatchDelegate.java:365` — 循环 slot 0-5 逐个重复退款。详见 [mods-storage-misc.md](./mods-storage-misc.md)。✅ 已修：同上守卫，6 槽循环内统一走 `refundToRSNetwork`。
- **[P1] PlayerUtils 退款丢物** — `PlayerUtils.java:62` — `network.insertItem` 忽略 remainder，网络满时静默吞物。详见 [infra-util.md](./infra-util.md)。✅ 已修：接住 remainder，未存入部分 fall through 到 world-spawn drop 分支。

## 后续复核状态

详见 [followup-status.md](./followup-status.md)：Aetherworks 双退款项已复核为已修复；Ars Nouveau 文档是设计计划而非已实现功能；所有 delegate 的异常生命周期仍未完成逐类审计。
