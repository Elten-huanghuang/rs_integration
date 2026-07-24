# crafting 核心域静态审计

审计范围（`crafting/` 目录直接文件，不含 graph/batch/plan/tree/loadbalancer 子目录）共 36 个 java 文件，逐个读完。重点文件：AsyncCraftChain、ExtractionLedger、CraftingResolver、CandidateEngine、ResolutionContext、RecipeIndex、StepExecutor、CraftPacketUtils、CraftNodeRuntime、OperationExecutionKernel/OperationResourceCoordinator、CraftOutputInterceptor、MaterialSources、IngredientMatcher、ActiveCraftRegistry、CaptureSession。

一句话总评：整体资源守恒、abort/超时路径、ledger 状态机、并发门控设计已相当成熟且对照历史坑做了针对性加固；**所有 P2 问题已在工作区修复**，无阻塞发布的问题。

> ⚠ **基线说明（2026-07-24 最终状态）**：本文档的发现是对照 **HEAD（已提交版本）** 写的。下列 3 条 P2 **已在工作区未提交 diff 中修复**：
> - [P2] executeVanillaStepsInline 双计副产物 —— 已删除 `tryGetSecondaryOutputs` 分支并补防重注释（工作区 AsyncCraftChain.java:2100-2112）。
> - [P2] RecipeIndex.getOutputs 主产物剔除漏隐藏 NBT —— 已补 `extractHiddenOutput` 修正 primary（工作区 RecipeIndex.java:699-703）。
> - [P2] tryGetResultItem 按字面名反射 vanilla getResultItem 在 SRG 下失效 —— 已补接口直调 `recipe.getResultItem(access)` 前置（工作区 RecipeIndex.java:555-561，符合"别反射原版方法、cast 直调"原则）。
>
> 审计当时对着 HEAD 的判断均属实、非误报；这三条是"发现时真实、随后被修"。当前仍有效的 P2 仅为“字段/方法签名校验机制全域未启用”；Curios 来源不一致项已复核关闭；其余 P3 仍为低优先观察项。

文件清单：PreviewRateLimiter、RSICraftException、GridTransferClassifier、MaterialSources、ChainRepeatController、CraftPlanningRevision、CraftProgressClientEvents、CraftProgressTracker、GraphExecutionPolicy、MaterialMatcher、OperationServices、TerminationCoordinator、CraftProgressSnapshot、ActiveCraftRegistry、ExtractionLedger、TerminalListeners、IngredientSpec、StepExecutor、CraftProgressKeybind、CraftProgressOverlay、CraftProgressPresentation、CraftProgressScreen、CraftPacketUtils、CaptureSession、OperationExecutionKernel、OperationResourceCoordinator、CraftNodeRuntime、CraftOutputInterceptor、OutputDestination、AsyncCraftManager、IngredientMatcher、AsyncCraftChain、RecipeIndex、CandidateEngine、ResolutionContext、CraftingResolver。

---

### [P2] executeVanillaStepsInline 对 CraftingRecipe 同时取 getSecondaryOutputs + getRecipeRemainders，与 executeCraftingSteps 的防重逻辑不一致（潜在刷物）
> ✅ **已在工作区修复**（见顶部基线说明）：`tryGetSecondaryOutputs` 分支已删除，仅保留 `getRecipeRemainders` + 防重注释。以下为 HEAD 版本的发现存档。
- 文件: crafting/AsyncCraftChain.java:2100-2113
- 维度: 资源守恒 / 算法正确性
- 现象: 在 `executeVanillaStepsInline` 的 CraftingRecipe 分支里，先 `ModRecipeHandlers.tryGetSecondaryOutputs(cr, ...)` 把结果加进 workingInventory（2100-2103），紧接着又 `CraftPacketUtils.getRecipeRemainders(cr, consumed)` 再加一遍（2107-2113）。
- 风险: 对于自定义 `CraftingRecipe` 子类若额外暴露无参 `getRemainingItems()/getByproducts()/getRollResults()/getOutputs()` 之类 getter，同一批副产物/remainder 会被 tryGetSecondaryOutputs 与 Forge 的 getRecipeRemainders 双重计入，刷入 RS 网络。对照历史坑「副产物字段扫描 + getOutputs() 含主产物 → 重复计入」「delegate collectResult 吞多产物」的同类。
- 证据: 同项目的 `CraftPacketUtils.executeCraftingSteps` 在同样的 CraftingRecipe 分支**只调用 getRecipeRemainders 且显式注释**（CraftPacketUtils.java:225-233）：“For CraftingRecipe, getRecipeRemainders (Forge API) already handles all remainders correctly — reflection-based scanning would duplicate them, causing a dupe exploit with buckets etc.” 两个 inline 执行器对同一类配方的处理相互矛盾，AsyncCraftChain 侧缺少该防重保护。纯原版配方因 vanilla `getRemainingItems(Container)` 是有参方法、无参反射探测命中不了而暂时安全，但模组配方不保证。

### [P2] RecipeIndex.tryGetSecondaryOutputs 的 getOutputs 主产物剔除依赖 isSameItemSameTags，隐藏 NBT 产物会漏剔导致主产物被当副产物重复计入
> ✅ **已在工作区修复**（见顶部基线说明）：拿到裸 primary 后已补 `extractHiddenOutput` 修正，剔除口径与真实主产物对齐（工作区 RecipeIndex.java:699-703）。以下为 HEAD 版本的发现存档。
- 文件: crafting/RecipeIndex.java:691-709
- 维度: 资源守恒
- 现象: `getOutputs()` 分支用 `primary = tryGetResultItem(recipe, access)` 求主产物，再对 getOutputs 列表按 `ItemStack.isSameItemSameTags(s, primary)` 只剔除一份主产物。
- 风险: 当模组 `getResultItem()` 返回“裸”主产物（无 NBT），而真正带 NBT 的主产物藏在 getOutputs 列表里（TACZ/Applied Armorer 这类隐藏 NBT 产物场景，见 CraftingResolver.extractHiddenOutput 的存在即证明该模式普遍），`isSameItemSameTags(裸, 带NBT)` 为 false → 主产物不被剔除 → 作为“副产物”返回并被 CraftPacketUtils.executeCraftingSteps(RecipeIndex.java 被 CraftPacketUtils.java:286 调用) 加入 virtualInventory 刷进网络。
- 证据: RecipeIndex.java:691 `ItemStack primary = tryGetResultItem(recipe, access);` 未走 extractHiddenOutput；对比 CraftingResolver.java:499-504 与 StepExecutor.java:203-208 在拿到裸产物后都会补 `extractHiddenOutput` 修正，唯独这里没有，剔除口径与真实主产物不一致。

### [P2] tryGetResultItem 按字面方法名反射 vanilla getResultItem/getResult 等，在 SRG 混淆环境下静默失效
> ✅ **已在工作区修复**（见顶部基线说明）：字面名反射前已补接口直调 `recipe.getResultItem(access)`（工作区 RecipeIndex.java:555-561），SRG 环境下走稳定的直调主路径。以下为 HEAD 版本的发现存档。
- 文件: crafting/RecipeIndex.java:568-599
- 维度: 反射安全 / 算法正确性
- 现象: 对非 CraftingRecipe、且无注册 handler 的配方，遍历 `clazz.getMethods()` 并用 `method.getName().equals("getResultItem"|"getResult"|"getOutput"|"getOutputCopy"|"getAssembledItem")` 反射取结果。
- 风险: `getResultItem`（`Recipe` 接口方法）在生产环境被 SRG 重映射为 `m_8043_`，`getName().equals("getResultItem")` 在 dev(MCP)为真、在 prod(SRG)为假 → 该探测路径在正式环境静默不命中，dev/prod 行为分叉。不会崩（不是 `getMethod` 抛异常，仅名字比较落空），但与历史坑「vanilla 方法运行时被 SRG 重映射」同源，属正确性隐患；模组自定义方法名（getOutput 等未被重映射）仍可命中，且有 tryGetOutputField 字段扫描兜底，故未升级为 P1。
- 证据: RecipeIndex.java:568 起的双层循环全部基于字面名字符串比较；注释「Skip no-arg getResultItem()」(585) 表明作者预期该名字可命中，但 SRG 下不成立。

### [P2][已关闭] findAvailableInInventory/reserveExactInventoryAvailability 统计背包(含 Curios)但物理提取只覆盖主/副手/护甲/背包，Curios 里的“非背包”容器可能出现预留成功却提取不足
> ✅ **2026-07-23 复核关闭（非缺陷）**：统计侧和提取侧均只处理 Sophisticated Backpack 的内部 `IItemHandler`，且都通过同一个 `findAllBackpackInventories(player)` 枚举普通背包槽、护甲/副手及 Curios 中的背包。Curios 中非背包容器两侧都不会计入，不存在口径不一致；未来扩展来源时应继续复用该 helper。
- 文件: crafting/ExtractionLedger.java:638-653, 542-568, 958-977
- 维度: 资源守恒 / Null 与异常
- 现象: 可用性统计 `reserveExactInventoryAvailability`/`findAvailableInInventory` 通过 `findAllBackpackInventories`（含 Curios 槽里的背包）累加数量；而 PLAYER_INVENTORY 的物理提取 `extractOne` 只从 `items/offhand/armor` + `extractFromBackpackSlots` 提取。
- 风险: 两侧口径基本对齐（都只认 Sophisticated Backpacks 的内部库存），暂无直接刷/丢证据；但一旦 `findAllBackpackInventories` 后续纳入更多来源而提取侧未同步，会出现“预留通过、提取不足”→ commit 阶段 `extractOne` 返回不足 → 走 rollbackExtractedPhases 清账（干净失败，不刷物）。目前为一致性脆弱点，标注待确认。
- 证据: 统计侧 ExtractionLedger.java:643-648 遍历 `findAllBackpackInventories`；提取侧 extractOne PLAYER_INVENTORY 分支(542-568)与 extractFromBackpackSlots(958-977)来源集合需与之严格同源，靠约定维系，无编译期保证。

### [P3] reserveGraphMaterials 消费失败返回 null 前，已对本地 producerPool 做了 shrink，依赖 MaterialBroker.checkout 为非破坏性拷贝 ✅ 已验证安全

**验证时间**：2026-07-24

- 文件: crafting/AsyncCraftChain.java:1562-1596
- 原问题: 循环里对 `producerPool` 元素 `produced.shrink(take)` 消费产物；若随后 initial 池失败即 `return null`，此时 producerPool 已被局部扣减，担心会影响 broker 内部状态
- 验证结果: `MaterialBroker.checkout(token)` 返回的是**深拷贝**：
  - `Checkout` 构造函数 `fragments = List.copyOf(fragments)`（不可变列表）
  - `Fragment` 构造函数 `stack = stack.copy()`（ItemStack 副本）
  - `checkout` 方法内 `lot.stack.copyWithCount(claim.quantity)`（再次拷贝）
- 结论: producerPool 是 checkout 返回的副本，对其 shrink 不影响 broker 内部状态；失败路径返回 null 后，caller 执行 `releaseMaterial(admission)` 正确释放预留资源。**无风险，设计正确。**

### [P3][已修复] tryGetSecondaryOutputs 顺序调用 getRemainingItems/getByproducts/getRollResults/getOutputs 且累加，多 getter 指向同一底层集合时会重复计入
> ✅ **已修复**：按返回集合对象身份去重；同一底层集合只采集一次。
- 文件: crafting/RecipeIndex.java:640-720
- 维度: 资源守恒
- 现象: 四个 getter 依次探测并累加进同一 `results`，仅在**四个 getter 全部无产出**时才 `trySecondaryOutputFields` 字段扫描兜底（716-718）。
- 风险: 若某配方同时实现语义等价的多个 getter（如 getByproducts 与 getRollResults 返回同一 list），会重复计入。作者已用「results.isEmpty() 才字段扫描」堵住 getter+字段双数的历史坑，但 getter 之间互相重复未去重。属边界场景，无具体模组证据，标注低优。
- 证据: RecipeIndex.java:643-711 四段各自 `results.add(...)`，之间无去重；仅 712-718 有 getter-vs-field 的互斥保护。

### [P3][已修复] finish() 中 tracker.changed 以 pre-insert 的 rsCandidate 上报，即使实际未全部插入网络
> ✅ **已修复**：tracker 现在按实际插入量 `InsertedStackDelta` 上报。
- 文件: crafting/AsyncCraftChain.java:3251-3257
- 维度: 代码质量
- 现象: `rsCandidate = leftover.copy()`（可能是玩家背包插入后的余量），随后无论 network.insertItem 实际吃进多少，只要 `!rsCandidate.isEmpty()` 就 `tracker.changed(online, rsCandidate)`。
- 风险: 仅影响 RS storage tracker 的变更通知/统计展示，不改变真实物品移动（真实插入以 insertItem 返回的 leftover 为准），无守恒影响，纯上报口径瑕疵。
- 证据: AsyncCraftChain.java:3253-3257，changed 用的是插入前 rsCandidate 而非 `InsertedStackDelta.between` 得到的实际插入量。

---

补充说明（已复查、判定无问题的历史坑同类项，供交叉验证）：
- 预算门控误报缺料：CraftingResolver.ensureIngredient 已在 timeout/ensureCalls 守卫**之前**先做直接扣库存（CraftingResolver.java:397-408 mayConsumeDirect），历史坑已修复，未复发。
- 同名重载递归守卫：RecipeIndex.tryGetResultItem 已加 `DISPATCH_GUARD` ThreadLocal 防 handler 回调无限递归（RecipeIndex.java:533-554），CraftPacketUtils.extractIngredients 有 `extractingIngredients` ThreadLocal 守卫（CraftPacketUtils.java:72,494-500）。
- 能力门控真正拦截：graph 路径 isNodeExclusive 用无副作用 probe（AsyncCraftChain.java:855-874）在 dispatch 前强制 exclusivity；并行路径 tryStartParallel 也在提交材料前用 concurrencyDecision.exclusive() 拦截（AsyncCraftChain.java:2663-2670），非仅 warn。
- 多步链 abort 丢物：committedVirtual 快照机制（snapshotCommittedVirtual/recoverCommittedVirtual）在各 settled 边界建立，abort 时只交付已消耗输入支撑的产物，in-flight 预留回滚丢弃，与 ledger 退款不重叠（AsyncCraftChain.java:3489-3491 及 committedVirtual 文档注释）。
- 副产物重复计入：AsyncCraftChain.tick DONE 分支对 secondary 有 GenericBatchDelegate/collectsPhysicalSecondaryOutputs/ParallelCraftGroup 三重分流防重（AsyncCraftChain.java:529-543）。
- 并发/线程安全：所有链推进在 onServerTick 单线程；跨线程共享的 CraftOutputInterceptor.ZONES/MaterialSources.cache 用 ConcurrentHashMap，entryIdSeq 用 AtomicInteger，未见可见性/竞态实证。
- OperationExecutionKernel.Session 状态机对 commit/tryStart/complete/settle 有严格 requireState 式守卫（IllegalStateException），permit 的 cancelBeforeStart vs close 区分正确（OperationResourceCoordinator.java:161-165）。
