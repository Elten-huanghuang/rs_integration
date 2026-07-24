# 基础设施/工具域静态审计 — util / machine / transfer / command / config / api / 根类

来源：workflow 抢救出候选，主会话对照真代码复核。`safeGiveToPlayer` 的丢物确认成立；`ContainerTransferClient` 重复注册监听确认成立（且在未提交 diff 中）。

一句话总评：一处退款丢物（网络满时忽略 insertItem 的 remainder），一处客户端渲染监听重复注册（性能/视觉，无数据损坏）。

---

### [P1][已关闭] PlayerUtils.safeGiveToPlayer 忽略 network.insertItem 返回的 remainder，网络满时静默吞物
> ✅ **已于 2026-07-23 在工作区修复**：接住 `insertItem` 余量，未存入部分 fall through 到 world-spawn drop 分支；已过 `./gradlew compileJava`。以下为修复前的发现存档。
- 文件: util/PlayerUtils.java:62
- 维度: 资源守恒
- 现象: 玩家所在区块未加载时的退款兜底分支（:62-65）：`network.insertItem(stack, stack.getCount(), Action.PERFORM)`，**丢弃返回值**。RS 的 `insertItem` 返回无法存入的剩余（remainder）。
- 风险: 当 RS 网络已满 / 无匹配存储 / 无网络电力时，insertItem 只存下一部分，剩余部分被静默丢弃 → 玩家退款物品凭空消失。方法自身注释（:44-47）恰恰声明它的存在目的是"fallbacks when the player's chunk is unloaded (which would silently void items)"——即本意就是**防止丢物**，此处却引入丢物。
- 证据: :62-65 `network.insertItem(stack, stack.getCount(), Action.PERFORM); RSIntegrationMod.LOGGER.warn(...)` 后直接返回，无 remainder 处理。对比 :66-75 的第三兜底（network==null 时 world-spawn drop）逻辑完整。
- 修复方向: 接住 `ItemStack remainder = network.insertItem(...)`，若 `!remainder.isEmpty()` 则落到 world-spawn drop 分支，保证不丢物。

### [P3][已关闭] ContainerTransferClient.init 重复注册同一 ScreenEvent.Render.Post 监听
> ✅ **已于 2026-07-23 在工作区修复**：删除重复的第二个 `addListener`。以下为修复前的发现存档。
- 文件: transfer/ContainerTransferClient.java:113-116
- 维度: 代码质量 / 性能
- 现象: `init()` 中两条完全相同的 `MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ScreenEvent.Render.Post.class, ContainerTransferClient::onRenderScreen)`（:113-114 与 :115-116），复制粘贴重复。
- 风险: `onRenderScreen` 每帧被调用两次，overlay/banner 重复绘制一遍（覆盖绘制，视觉上多半不可见但浪费一次渲染）；纯客户端，无数据损坏。属未提交 diff 的新增代码。
- 证据: :113-116 两处字面完全一致。删其一即可。

---

## 待补审
本域还含 machine/（12）、command、config、api、根类（RSIntegrationMod/ModItems/ModType/ModVersionDelegateRegistry），尚未逐文件通读。后续主会话补齐。
