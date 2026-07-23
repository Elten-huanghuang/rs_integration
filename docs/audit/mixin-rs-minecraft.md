# Mixin 审计：refinedstorage / minecraft / jei / plugin

## 文件清单（36 个）

### refinedstorage（17）
- NetworkItemUseMixin.java · IngredientTrackerMixin.java · CraftingGridBehaviorMixin.java
- GridScreenTooltipMixin.java · GridScreenKeyboardMixin.java · StackUtilsMixin.java
- TooltipGridFilterMixin.java · TagGridFilterMixin.java · ModGridFilterMixin.java
- GridTransferMessageAccessor.java · MixinGridTransferMessage.java · GridScreenMachineTabMixin.java
- CraftingManagerMixin.java · CraftingTaskAccessor.java · CraftingTaskMixin.java
- ItemGridHandlerMixin.java · GridScreenMouseMixin.java

### minecraft（13）
- AbstractFurnaceAccessor.java · ISmithingRecipeAccessor.java（含 SmithingTransform/TrimRecipeAccessor 两接口）
- MinecraftMixin.java · InventoryAccessor.java · ServerGamePacketListenerMixin.java
- ItemEntityAccessor.java · ContainerDistanceMixin.java · EntityMixin.java
- FurnaceMenuMixin.java · ServerPlayerTickMixin.java · ServerPlayerMessageMixin.java
- MerchantScreenAccessor.java · MerchantScreenSelectionMixin.java

### jei（5）
- GuiIconToggleButtonAccessor.java · RecipeBookmarkMixin.java · RecipesGuiMixin.java
- BookmarkOverlayAccessor.java · RecipeGuiLayoutsMixin.java

### plugin（1）
- RSIntegrationMixinPlugin.java

## 总评
整体质量较高：accessor/invoker 对 vanilla 目标均走 refmap 重映射；对 RS/JEI 目标一律 `remap=false`，符合“非原版类不重映射”的正确惯例；plugin 的 `shouldApplyMixin` 对 body 硬引用第二个 mod 的 mixin 做了资源探针守卫，RS/JEI 目标依赖 Mixin 框架“目标类缺失即自动跳过”的语义（正确）。**未发现 P0/P1 级物品复制、丢失或崩溃**。主要问题集中在 P2：一处 Entity 距离注入过宽（改变原版语义、热路径）、一处 client-only mixin 被登记进 common 列表、一处注入合成 lambda 的脆弱目标 + `defaultRequire=0` 静默失效。其余为 P3 健壮性/一致性。

---

## P2

### [P2] EntityMixin 对 distanceToSqr/distanceTo 的旁路过宽，改变原版全局语义且位于热路径
> ✅ **已于 2026-07-23 在工作区修复**：保留模组自定义菜单所需的距离兜底，但新增 `RemoteGuiAuth.isAuthorizedDistanceTarget`，仅对当前授权机器中心 2 格内的查询返回 0；无关 AI/追踪/声音距离恢复原版语义。已过 `compileJava`。
- 文件: src/main/java/com/huanghuang/rsintegration/mixin/minecraft/EntityMixin.java:14-55
- 维度: Mixin 正确性 / 算法语义 / 性能
- 现象: 对 `Entity.distanceToSqr(DDD)`、`distanceToSqr(Vec3)`、`distanceToSqr(Entity)`、`distanceTo(Entity)` 四个方法做 HEAD `@Inject`，只要 `this` 是已授权（`RemoteGuiAuth.isAuthorizedCurrentMenu`）的 ServerPlayer，就无条件返回 `0`。
- 风险: 容器距离校验已由 ContainerDistanceMixin / FurnaceMenuMixin / ServerGamePacketListenerMixin / ServerPlayerTickMixin 覆盖；此处再直接改写 Entity 距离方法，会让授权窗口内该玩家的**所有**距离查询都读到 0——不仅是容器 stillValid，还包括生物 AI 索敌、声音衰减、tracking、任何调用 `player.distanceToSqr(...)` 的第三方逻辑，可能造成授权期间的行为异常。同时 `distanceToSqr` 是全实体热路径，每次调用都新增 `instanceof Player` 判断（服务端玩家少，开销以非玩家实体的 instanceof 为主，可接受但非零）。
- 证据: `if ((Object) this instanceof Player player) { if (player instanceof ServerPlayer sp && RemoteGuiAuth.isAuthorizedCurrentMenu(sp)) { cir.setReturnValue(0.0D); cir.cancel(); } }`（四个方法同构）。建议缩小到仅覆盖真正需要距离旁路的具体 stillValid 实现，或用更窄的判定门。

### [P2] IngredientTrackerMixin 登记在 common mixin 列表，但方法体硬引用 client-only 的 Minecraft.getInstance()
> ✅ **复核当前工作区已修复**：`IngredientTrackerMixin` 已位于 `rs_integration.mixins.json` 的 `client` 数组，不在 common `mixins` 数组。以下为旧基线发现。
- 文件: src/main/java/com/huanghuang/rsintegration/mixin/refinedstorage/IngredientTrackerMixin.java:61,68; src/main/resources/rs_integration.mixins.json:26
- 维度: 端隔离 / Mixin 正确性
- 现象: `IngredientTrackerMixin` 在 mixins.json 位于普通 `"mixins"` 数组（第 26 行），而非 `"client"` 数组；其 `addStack` 注入体调用 `PatternItem.fromCache(Minecraft.getInstance().level, stack)`（client 专属）。
- 风险: 若目标类 `com.refinedmods.refinedstorage.integration.jei.IngredientTracker` 在专用服务端被类加载，注入的 client 引用会 NoClassDefFoundError。当前仅靠“RS 的 JEI 集成类在专用服务端不会被加载”这一隐性事实兜底，属脆弱前提。同类的 CraftingGridBehaviorMixin / MixinGridTransferMessage / StackUtilsMixin / NetworkItemUseMixin 确实是服务端逻辑，放 common 正确；唯独本项应归入 `"client"` 列表。
- 证据: mixins.json line 26 `"refinedstorage.IngredientTrackerMixin"` 在 common 段；文件 line 61 `PatternItem.fromCache(Minecraft.getInstance().level, stack)`。

### [P2] MixinGridTransferMessage 注入合成 lambda `lambda$handle$0`，配合 defaultRequire=0 会静默失效
> ✅ **已于 2026-07-23 在工作区加固**：该关键注入单独设置 `require = 1`，目标漂移时启动期明确失败，不再静默丢失回吐逻辑。合成 lambda 目标仍是上游版本兼容约束，升级 RS 时需验证。
- 文件: src/main/java/com/huanghuang/rsintegration/mixin/refinedstorage/MixinGridTransferMessage.java:33-41; rs_integration.mixins.json:77
- 维度: Mixin 正确性
- 现象: `@Inject(method = "lambda$handle$0", at = INVOKE onRecipeTransfer, shift = AFTER)`，目标是编译器生成的合成 lambda 方法名。mixins.json `injectors.defaultRequire = 0`。
- 风险: 合成 lambda 名（`lambda$handle$N` 的编号）随 RS 源码里 handle() 内 lambda 数量/顺序变化而漂移；一旦 RS 改版导致名字或注入点 INVOKE 签名变化，因 `defaultRequire=0` 注入静默失败（不报错），“非 crafting 配方物料回吐到玩家背包”的逻辑会悄悄失效——转移进网格的非 crafting 配方物料会残留在网格里，用户不易察觉。建议对该关键注入单独设 `require = 1` 以在失配时早失败。
- 证据: `method = "lambda$handle$0"` + `defaultRequire: 0`。

---

## P3

### [P3] StackUtilsMixin “只允许一块共振盘”的判定依赖 createStorages 的逐槽调用顺序
- 文件: src/main/java/com/huanghuang/rsintegration/mixin/refinedstorage/StackUtilsMixin.java:25-38
- 维度: 算法正确性
- 现象: TAIL 注入里遍历其余槽位，发现已有另一块共振盘就把当前槽 `itemDisks[slotIndex]=null; fluidDisks[slotIndex]=null;`。
- 风险: 结果“保留哪一块”取决于 createStorages 对各槽的调用先后（先建的槽会成为保留者）；行为上仍能保证“最多一块生效”，但被禁用的槽同时把 `fluidDisks[slotIndex]` 也置空（即便共振盘是 item 盘），若该槽存在独立 fluid 盘会被误清。属边界健壮性，非丢数据（盘 NBT 未动，仅本次不激活）。

### [P3] RecipeGuiLayoutsMixin 中对 vanilla 方法按 dev 名反射（getId），靠 SRG 名兜底
- 文件: src/main/java/com/huanghuang/rsintegration/mixin/jei/RecipeGuiLayoutsMixin.java:726-733
- 维度: 反射安全
- 现象: `Reflect.findMethod(recipe.getClass(), "getId", ...)`，失败再 `findMethod(..., "m_6423_", ...)`。
- 风险: 生产环境 vanilla `Recipe.getId` 实为 `m_6423_`，“getId”探针恒失败，永远靠第二次 `m_6423_` 命中——正是记忆里“别反射原版方法”的坑，只是此处有 SRG 兜底未致命。若未来 vanilla 方法签名/映射变化，兜底也会失效。属可接受但需留意。

### [P3] 生产 SRG 方法名 + remap=false 的注入在 deobf 开发环境不生效
- 文件: GridScreenMouseMixin.java:79,131,158,168; GridScreenKeyboardMixin.java:14,46; GridScreenTooltipMixin.java:21; RecipesGuiMixin.java:23
- 维度: Mixin 正确性
- 现象: 对 RS/JEI 屏幕继承自 vanilla Screen 的方法用 SRG 名（`m_6375_`/`m_6348_`/`m_7979_`/`m_6050_`/`m_7933_`/`m_5534_`/`m_280003_`）+ 类级 `remap=false`。
- 风险: 生产（obf/SRG）正确；deobf 开发环境这些方法名为 `mouseClicked` 等，注入找不到目标 → dev 下功能缺失。属团队既定“面向生产”取舍，统一记录一次。

### [P3] RecipeGuiLayoutsMixin.setRecipeLayoutsWithButtons(RETURN) 未空判 Minecraft.player
- 文件: src/main/java/com/huanghuang/rsintegration/mixin/jei/RecipeGuiLayoutsMixin.java:122,166,311
- 维度: Null 与异常
- 现象: `var player = Minecraft.getInstance().player;`（122）后，quest 分支直接 `player.level()`（166）及后续多处使用 player，均未空判；该 RETURN 注入未包 try/catch。
- 风险: 正常打开 JEI GUI 时 player 必然存在，触发概率极低；但一旦为 null 会 NPE 冒泡进 JEI 的 setRecipeLayoutsWithButtons，可能破坏配方 GUI。低概率潜在 NPE。

### [P3] Goety SummonRitual 的“跳过/保留”与注释自相矛盾（待确认）
- 文件: src/main/java/com/huanghuang/rsintegration/mixin/jei/RecipeGuiLayoutsMixin.java:214-219,1476
- 维度: 算法正确性
- 现象: 循环处注释（214-216）称要跳过 `SummonRitual`（无物品产出）；但 `rsi$isGoetyNonItemRitual` 对 `SummonRitual` `return false`（=不视为非物品仪式=保留），故只要非 sacrificial 就不会被跳过，会给它生成“+”按钮。
- 风险: 为无物品产出的召唤仪式生成合成按钮，点击可能无效或报错（UX 不一致）。是否有意保留召唤仪式的远程触发，需确认。

### [P3] MinecraftMixin.setScreen 拦截依赖 GuiNavStack 不回传同一关闭屏，无自防护
- 文件: src/main/java/com/huanghuang/rsintegration/mixin/minecraft/MinecraftMixin.java:14-26
- 维度: 递归/栈安全
- 现象: newScreen==null 时若 `popRestoreTarget(closing)` 返回非空，`ci.cancel()` 后在 HEAD 注入内再次 `setScreen(restore)`。
- 风险: 若 restore==closing（同一实例），会重入死循环。当前依赖 GuiNavStack 语义保证 restore≠closing 且不为 null；建议加一道 `restore != closing` 自防护。

### [P3] ServerPlayerMessageMixin 在 silent 作用域内抑制该玩家全部系统消息
- 文件: src/main/java/com/huanghuang/rsintegration/mixin/minecraft/ServerPlayerMessageMixin.java:19-22
- 维度: Null 与异常 / 状态一致性
- 现象: `sendSystemMessage(Component,boolean)` HEAD 注入，`if (PreparationMessageScope.isSilent()) ci.cancel();`——静默期抑制**所有**系统消息，非仅 preparation 消息。
- 风险: 依赖 PreparationMessageScope 在 finally 中复位；若作用域泄漏（异常路径未复位），玩家将持续收不到任何系统消息。需确认 scope 用 try/finally 严格配对（源不在本域）。

### [P3] CraftingGridBehaviorMixin 的 NBT 无关抽取兜底可能取到错误 NBT 变体
- 文件: src/main/java/com/huanghuang/rsintegration/mixin/refinedstorage/CraftingGridBehaviorMixin.java:26-31
- 维度: 算法语义
- 现象: 精确抽取返回空时，回退到 `flags & ~COMPARE_NBT` 再抽取一次。
- 风险: 忽略 NBT 抽取可能把非目标 NBT 变体（如另一本附魔书）塞进合成网格。已由 `ENABLE_BINDING` 门控且仅作兜底，属可接受的设计取舍，记录备查。

### [P3] 重复注释块 / 冗余 cir.cancel()
- 文件: RecipeGuiLayoutsMixin.java:251-264（FA smithing 注释整段重复一次）；EntityMixin.java:47-55（`distanceTo(Entity)F` 只调用 `setReturnValue` 未 `cancel()`，而另三处 `setReturnValue`+`cancel` 并存）
- 维度: 代码质量
- 现象/风险: 前者为复制粘贴留下的重复注释；后者因 Mixin `CallbackInfoReturnable.setReturnValue` 本身即隐含 cancel，功能无差异，仅风格不一致。均为可维护性问题，无功能风险。

---

## 已确认良好 / 无问题项
- plugin `shouldApplyMixin`：对 body 硬引用第二 mod 类的 mixin（goetydelight/distantworlds/apotheosis/ironfurnaces/forbidden/CraftingManager 系/namelesstrinkets/yuusha/addon/moonstone/terraequipment）逐一做 `isClassPresent` 资源探针；`isClassPresent` 用 classLoader.getResource 而非 `Class.forName`，正确规避 ReEntrantTransformerError。RS/JEI 目标 mixin 依赖框架“目标缺失自动跳过”，无需额外守卫，正确。
- accessor/invoker（AbstractFurnaceAccessor、InventoryAccessor、ItemEntityAccessor、ISmithingRecipeAccessor、MerchantScreenAccessor、CraftingTaskAccessor、GridTransferMessageAccessor、GuiIconToggleButtonAccessor、BookmarkOverlayAccessor）：vanilla 目标默认走 refmap 重映射、RS/JEI 目标 `remap=false`，字段/方法名与 1.20.1 一致。
- CraftingManagerMixin / ItemGridHandlerMixin / CraftingTaskMixin：requester 记忆与产出上报守恒，`forget()` 保证 per-task 幂等；`@Redirect` 保留原 `manager.start(task)`，无副作用吞没。
- RecipeBookmarkMixin：`@Redirect getRegistryName` 兜底 pseudo-ID 已注明不持久化，会话内可用，合理。
- ServerGamePacketListenerMixin / ContainerDistanceMixin / FurnaceMenuMixin / ServerPlayerTickMixin：stillValid 旁路均以 `RemoteGuiAuth.isAuthorized` 门控且限定 ServerPlayer，端与作用域正确。
