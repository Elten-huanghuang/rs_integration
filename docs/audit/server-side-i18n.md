# 专用服务器端 i18n 失效审计

> 状态：根因已确认，修复已落地（2026-07-25）。

## 现象

mod 作为专用服务器（dedicated server）运行时，客户端部分文本不显示汉化，
而是显示原始 translation key（如 `rsi.plan.failure.missing_materials`）或
原始 descriptionId（如 `item.embers.xxx`）。单机（集成服务端）完全正常。

## 根因

反编译 Forge 47.3.22 确认：

- `net.minecraftforge.server.LanguageHook.loadLanguagesOnServer()` 在专用服务器上
  只加载 `data/<namespace>/lang/en_us.json`（服务端资源/数据包），
  外加 classpath 上的 `assets/minecraft/lang/en_us.json` 与 `assets/forge/lang/en_us.json`。
- 本 mod 的翻译位于 `assets/rs_integration/lang/{en_us,zh_cn}.json`，
  属于**纯客户端资源**，专用服务器永不加载。
- 因此服务端执行 `Component.translatable("rsi.foo").getString()` 时，
  `Language.getOrDefault()` 找不到条目，回退返回 key 本身 `"rsi.foo"`；
  `ItemStack.getHoverName().getString()` 同理返回 `item.somemod.thing`。
- 单机下客户端与服务端同进程共享 `Language` 实例，故此类 bug **完全不可见**，
  只在多人环境暴露。

**通用判据**：任何在服务端把 `Component` / `ItemStack` 解析成 `String`、
再经 packet 发往客户端的代码，在多人游戏下都会产出未翻译的原始 key。

**通用修法**：发送 translation key（或直接发 `Component` / `ItemStack`），
由客户端解析。

## 与翻译文件无关

`en_us.json` 与 `zh_cn.json` key 集合完全一致（9 条 value 相同，属专有名词，非漏译）。
`src/test/java/com/huanghuang/rsintegration/LanguageParityTest.java` 已守住 parity。
故本问题**不是缺 key 导致**。

## 已修复点位

### 1. 计划警告链（影响面最大）

所有 delegate 的 `getPlanWarnings(ServerPlayer, Recipe<?>, ResourceLocation, BlockPos)`
均为服务端签名，原先内部用 `Component.translatable(...).getString()` 构造 `List<String>`，
经 `crafting/plan/PlanWarnings.java` 的 `collect()` 中央分发汇总，
最终进入 `PlanResponse.modWarnings()` 并由 `PlanResponsePacket` 的 `writeUtf` 发往客户端。

代表点位（修复前）：

```java
// mods/aetherworks/AetherworksBatchDelegate.java
warnings.add(Component.translatable("rsi.aetherworks.warn.temp_range", min, max).getString());
```

已改为返回 `List<Component>`：`PlanWarnings.collect` 与 24 个 delegate 的
`getPlanWarnings` 全部转换，共移除 74 处 `.getString()`。
按数量分布：Embers 12、FA 8、Eidolon 8、Aetherworks 6，其余各 1–4 处。

### 2. 计划失败原因（玩家最常撞到的主路径）

`crafting/batch/GenericCraftPacket.java` 中四条失败提示
（机器被占用 / NBT 不匹配 / 材料不足 / 未绑定机器），
外加 Botania 总魔力与 Ars 总 source 两条统计警告。

### 3. Embers 基座名称

`mods/embers/EmbersPlanInfo.java` 用 `getHoverName().getString()` 取物品名塞进
`aspectNames` / `inputNames`，多人下显示 `item.embers.xxx`。
两个数组已改为 `Component[]`。

### 4. `sendPlanError`

原先把消息塞进 `missing` 列表。`missing` 恰好是客户端 `localizeItemNames()`
唯一重新本地化的字段，但它只对纯 key 生效，而调用方已 `.getString()` 过，
故二次本地化扑空。已改为接收 `Component` 并改走 `modWarnings`。

### 5. 其他散点

`formatMissingSummary`（改为返回 `Component`，每个物品名作可翻译子节点）、
Goety 的 `resolveCraftTypeName` / `resolveResearchName`、
Market 交易提示、AutoEat 结果、FA 仪式产物名，
以及 Eidolon / TLM 中 `Component.literal("§c" + ....getString())` 的字符串拼接。

## 原本就正确的部分

- `missing` 列表：`CraftingResolver.describeItem()` 发 `getDescriptionId()`，
  客户端 `localizeItemNames` 解析。
- 侧板机器名：`RSSidePanelNetworkHandler.resolveDisplayName()` 返回翻译 key，
  客户端 `I18n.get`。
- 聊天消息：绝大多数 `sendSystemMessage(Component.translatable(...))` 整体发
  `Component`，由客户端解析。
- `machineLabel`：内容是坐标，非翻译文本。

## 回归防线

`src/test/java/com/huanghuang/rsintegration/ServerSideTranslationBytecodeTest.java`
用 ASM 扫描 `build/classes/java/main`：若一个非客户端类的某方法同时
加载 `rsi.` 字符串常量并调用 `Component.getString()`，即构建失败。

客户端类通过 `@OnlyIn(Dist.CLIENT)` 与路径特征豁免；
经人工确认为纯日志用途的方法在 `LOG_ONLY_ALLOWLIST` 中逐条列名
（按方法而非按包豁免，故同一方法内新增的真实问题仍会被拦住）。

