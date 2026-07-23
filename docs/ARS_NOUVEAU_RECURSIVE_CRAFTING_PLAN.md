# Ars Nouveau 递归合成集成计划

> 本文已按 4.12.6 目标 JAR 的反编译源码逐条核对。所有"已确认"条目均能在
> 对应 tile/recipe 类中定位到证据。带 ⚠ 的条目是对旧版计划中错误或缺失的修正。

## 1. 范围与依据

目标版本：

```text
Minecraft: 1.20.1
Forge: 47.x
Mod: Ars Nouveau 4.12.6
JAR: libs/[新生魔艺] ars_nouveau-1.20.1-4.12.6-all.jar
Mod ID: ars_nouveau
```

反编译核对覆盖：`EnchantingApparatusTile`、`ImbuementTile`、`ArcanePedestalTile`、
`SingleItemTile`、`AbstractSourceMachine`、`SourceManager`/`SourceUtil`、
`IEnchantingRecipe` 全部实现、`IImbuementRecipe`/`ImbuementRecipe`、以及
`setup/registry/RecipeRegistry` 中注册的全部 15 种配方类型。

现有 RSI 框架契约以 `crafting/batch/IBatchDelegate.java`、`AbstractBatchDelegate`、
`BatchConcurrencyCapabilities` 为准，命名与生命周期对齐 `mods/eidolon`、`mods/malum`
两个最接近的类比模块（基座 + 中央槽 + 非物品资源）。

## 2. 已确认语义

### 2.1 Enchanting Apparatus

- 中央 `EnchantingApparatusTile extends SingleItemTile`，单槽保存核心输入或结果。
- 四周 `ArcanePedestalTile`（同样 `extends SingleItemTile`，单槽）提供配方材料，
  `pedestalList()` 半径固定为 1。
- ⚠ 触发方法确实是 `attemptCraft(ItemStack catalyst, @Nullable Player)`，**player 可传
  null**（tile 内部 `tick()` 就以 null 调用 `getRecipe`）。因此"需要玩家"并不是障碍，
  RSI 可在无玩家上下文时直接调用。
- ⚠ 完成检测不能读中央槽：`m_8020_(int)` 在 `isCrafting` 为真时**返回空栈**。必须观测
  `public boolean isCrafting` 与 `private int counter`（`counter > craftingLength(=210)`
  时结算），结算瞬间把结果写回中央槽并置 `isCrafting=false`。
- 合成消耗 Source（见 2.3），结果 `recipe.getResult(pedestalItems, catalyst, tile)`
  回到中央内部槽。
- `SingleItemTile` 暴露 `ForgeCapabilities.ITEM_HANDLER = new InvWrapper(this)`，可自动
  放置/取回；但取回前必须先确认 `isCrafting=false`，否则读到空。
- 机器和基座属于一个空间结构，绑定时不能只保存中央方块而忽略基座集合。

### 2.2 Imbuement Chamber

- ⚠ **不是纯单方块机器**：`ImbuementTile implements IPedestalMachine`，`ImbuementRecipe`
  带 `pedestalItems` 列表，`isMatch()` 要求 `getNearbyPedestals()`（半径 1）上的物品
  数量与内容都匹配。原版一半的注魔配方（各元素精华）需要 3 个装好料的 Arcane Pedestal。
  只有 `pedestalItems` 为空的配方（如 `imbuement_amethyst`）才是真正的单槽场景。
- 槽 0 同时是输入与输出：容器大小为 1，底层是单个 `ItemStack stack` 字段（非
  `ItemStackHandler`），完成时 `m_6836_(0, recipe.getResult(this).copy())` 覆盖同一槽。
- ⚠ 插入槽 `m_7013_(slot, stack)` 是**配方门控**的：它临时写入 `this.stack` 试算
  `getRecipeNow()` 再还原，只有能形成有效配方才允许插入。这是一个天然的"机器已接受输入"
  探针，但也意味着**该探针有副作用、只能主线程调用**。
- ⚠ 取出槽 `m_8016_(int)` **完全无守卫**：任何漏斗/磁铁都能在 ≥100 tick 的合成窗口内
  把原料抽走（不产生复制，但会静默丢失到错误产物）。RSI 自身轮询到 `slot0==output`
  再取，可保护自己的取件，但挡不住外部行为体抢同一个无守卫槽。
- 放入有效输入后自动运行（`tick()` 里 `ITickable`），无需点击。
- ⚠ **Source 是软节流不是硬门槛**：`tick()` 每 20 tick 若 `source<cost` 就
  `SourceUtil.takeSource(pos, level, 2, min(200,cost))`（半径 2）补源；**附近无源时仍
  `addSource(10)` 兜底**。所以注魔永不会因缺源永久卡死，只会变慢——"缺源即 WAITING"
  的模型对注魔过严，应视作吞吐问题而非可行性问题。
- ⚠ 有 100 tick 的**最小合成时长**（`craftTicks` 初值 100），与源无关。委托超时须计入
  `max(100 tick, 累源所需 tick)`；原版配方 source 成本在 500~2000 量级。
- 完成判定：`getSource() >= cost && craftTicks <= 0`。仅"槽非空"无意义，必须比较
  slot0 是否等于 `recipe.getResult(tile)` 且不同于输入。

### 2.3 Source 不是物品材料

- Source 是 `AbstractSourceMachine` 上的一个 per-tile `int`（`getSource/addSource/
  removeSource/setSource`，`ImbuementTile.getMaxSource()=10_000_000`）。
- ⚠ 取源**非原子、无锁、尽力而为**：`SourceManager.takeSourceNearby` 只是遍历附近
  provider 逐个 `removeSource`。**无法证明预留原子性**（见 §9 修正）。
- Source 不应转换成 `IngredientSpec`，不进入材料 DNF；应在预览中作为机器资源/警告展示，
  执行阶段由 delegate 检查与等待。Source 不足时不得提前扣除配方物品。

## 3. 配方类型全景与可自动化判定

`setup/registry/RecipeRegistry` 注册了 15 种自定义 `RecipeType`。旧版计划只覆盖 2 种，
这是"写不全"的主因。下表是权威覆盖矩阵——**只有确定物品产物、无随机、无实体/世界上下文
的类型才进自动合成候选索引**。

| RecipeType | 配方类 | 机器 | 产物 | 自动化裁定 |
|---|---|---|---|---|
| `imbuement` | `common.crafting.recipes.ImbuementRecipe` | Imbuement Chamber | 确定 ItemStack(item,count)，无 NBT | ✅ Phase 1A（含基座变体） |
| `enchanting_apparatus` | `api...EnchantingApparatusRecipe` | Apparatus | 确定 result 物品 | ✅ Phase 1B |
| `enchantment` | `EnchantmentRecipe extends EnchantingApparatusRecipe` | Apparatus | 对输入附魔（NBT 变换） | ⚠ TRANSFORMED，见 §4.1 |
| `armor_upgrade` | `ArmorUpgradeRecipe extends …, ITextOutput` | Apparatus | 对护甲改 perk（NBT 变换）+ 聊天输出 | ❌ 排除（NBT 变换 + ITextOutput） |
| `spell_write` | `SpellWriteRecipe extends …, ITextOutput` | Apparatus | 写法术到卷轴（NBT 变换） | ❌ 排除 |
| `reactive` | `ReactiveEnchantmentRecipe extends EnchantmentRecipe` | Apparatus | 反应附魔（NBT 变换） | ❌ 排除 |
| `crush` | `CrushRecipe` | Crushing 相关 | **随机** `getRolledOutputs(RandomSource)`，逐项 chance/maxRange | ❌ 排除（随机） |
| `glyph` | `GlyphRecipe` (Recipe\<ScribesTile\>) | Scribes Table | 确定物品 | ⏳ Phase 2 候选，需先验 Scribes 生命周期 |
| `dye` | `DyeRecipe extends ShapelessRecipe` | 工作台类 | 依赖输入染色（NBT/颜色变换） | ❌ 排除 |
| `potion_flask` | `PotionFlaskRecipe extends ShapelessRecipe` | 工作台类 | 药水瓶（NBT 依赖） | ❌ 排除 |
| `book_upgrade` | `BookUpgradeRecipe extends ShapelessRecipe` | 工作台类 | 法术书升级（NBT 变换） | ❌ 排除 |
| `caster_tome` | `CasterTomeData` | — | 法术数据 | ❌ 排除 |
| `summon_ritual` | `SummonRitualRecipe` | Ritual Brazier | **实体**生成 | ❌ 排除（实体） |
| `scry_ritual` | `ScryRitualRecipe` | Ritual Brazier | 世界副作用 | ❌ 排除（世界） |
| `budding_conversion` | `BuddingConversionRecipe` | — | 方块转化 | ❌ 排除（世界方块） |
| `dispel_entity` | `DispelEntityRecipe` | — | **实体**上下文 | ❌ 排除（实体） |

结论：第一阶段可自动化的只有 **Imbuement** 与 **Enchanting Apparatus 的普通
`enchanting_apparatus` 子类型**。`glyph`（Scribes Table）是 Phase 2 唯一现实候选，但需
先完成 Scribes 生命周期反编译验证。其余 12 种默认排除，并在索引层给出排除原因。

## 4. 支持阶段

### Phase 1A：Imbuement Chamber

单方块自动机，生命周期最接近现有物理 delegate。⚠ 但必须处理两类配方：

- **无基座变体**（`pedestalItems` 空）：纯单槽，直接放入触发。
- **基座变体**（元素精华等）：必须像 Apparatus 一样占用并布置周围基座，否则
  `m_7013_` 会拒绝插入、配方永不匹配。这抹去了"注魔比法阵简单"的前提差距。

需要新增：

```text
ArsNouveauRSModule
ArsNouveauReflection      (probe：模组自身类/方法/字段)
ArsImbuementRecipeHandler
ArsImbuementBatchDelegate
ArsPedestalLayout         (⚠ 提前到 1A，注魔基座变体也要用)
ArsSourceProbe
```

### Phase 1B：Enchanting Apparatus

在 Imbuement 稳定后实现。需要空间基座分配、Source 检查、`attemptCraft` 显式启动，
以及 `isCrafting`/`counter` 完成检测。

需要新增：

```text
ArsApparatusRecipeHandler
ArsApparatusBatchDelegate
```

（`ArsPedestalLayout`、`ArsSourceProbe`、`ArsNouveauReflection` 在 1A 已建。）

### Phase 2：Scribes Table（glyph）——唯一现实扩展候选

仅在逐一确认后接入：权威配方源与产物提取、输入来源、`ScribesTile` 生命周期、
是否有随机/世界副作用、中止可否无损恢复。其余机器默认不进候选索引。

## 5. 配方索引设计

### 5.1 显式 handler（对应 §3 裁定）

每种 Ars 配方使用显式 handler，不依赖通用 `getResultItem(RegistryAccess)`：

- ⚠ **`ImbuementRecipe` 的 `assemble()` 与 `getResultItem()` 都返回 `ItemStack.EMPTY`**。
  必须调用 `getResult(tile)`（返回固定 `output` 字段的**共享实例**，须 `.copy()` 再用，
  且 `output` 无 NBT、count 来自 JSON）。这直接验证了"不得只依赖通用 getResultItem"。
- ⚠ **子类型陷阱**：`EnchantmentRecipe`/`ArmorUpgradeRecipe`/`SpellWriteRecipe`/
  `ReactiveEnchantmentRecipe` 全部 `extends EnchantingApparatusRecipe`。因此分类**不能用
  `instanceof EnchantingApparatusRecipe`**（会把 NBT 变换子类型一起吞进来）。必须用
  `recipe.getClass()` 精确类名或 `RecipeType` 精确匹配来区分普通合成与变换子类型。
- handler 职责：校验具体 recipe class / recipe type；从权威字段读产物；返回准确
  `IngredientSpec`；标记容器返还物/可复用物/普通消耗物；对空产物、随机产物（Crush）、
  实体产物（summon/dispel）关闭自动化。
- ⚠ 反射约定：Ars 是第三方 mod，其自身方法（`attemptCraft`、`getPedestalItems`、
  `getResult`、`getSourceCost`、`isMatch`）**走反射 probe**（照 `EidolonReflection`
  模式）；只有**原版 tile 方法**（`setChanged()` 等）才 cast 直调——原版方法运行时被
  SRG 重映射，反射会崩。这条务必写进 `ArsNouveauReflection`。
- 输出解析不得回调 `RecipeIndex.tryGetResultItem`，以免 handler 递归（本仓库已有过
  `tryGetResultItem` 递归炸栈的历史）。

### 5.2 候选索引

- 只索引能得到确定物品输出的配方（§3 表中 ✅ 项）。
- 同一 recipe ID 只生成一个 `RecipeIndex.Entry`。
- 次要输出只有真机器能稳定收集时才声明（Imbuement/Apparatus 均无次要产物）。
- ⚠ 配方 reload 清缓存有真实钩子：`GenericRecipeRegistry.reloadAll` 由
  `EventHandler.resourceLoadEvent(AddReloadListenerEvent)` 与 `ServerStartedEvent`
  触发。RSI 必须监听同一时机清理结果缓存、反射缓存、机器可用性缓存。

## 6. 统一需求模型

Ars 配方必须保留输入角色：

```text
CONSUMED            每次合成都消耗（reagent / 注魔输入 / 基座材料）
CATALYST            必须存在，批量期间只保留最低数量
CONTAINER_RETURNING 每次参与并产生剩余物（Apparatus 结算走 getCraftingRemainingItem）
TRANSFORMED         输入 NBT/耐久影响返回物或结果（enchantment 子类型——已排除自动化）
```

⚠ 对齐 `IBatchDelegate.MaterialReservationScope`：CATALYST → `PER_WORKER_REUSABLE`，
其余 → `PER_OPERATION`（默认实现已按 `DemandRole.CATALYST` 归类）。

Source 不属于上述物品角色。批量规划示例（注魔）：

```text
输入 A x1 + 基座料 B/C/D 各 x1 + 500 Source -> 产物 E
制作 10 次：A x10、B/C/D 视 §7 基座保留策略；执行资源为累计 Source（软节流）
```

Source 只限制启动/继续速度，不让 `available / requiredPerCraft` 参与物品批次数计算。

## 7. 机器绑定与注册

⚠ 绑定注册照 `EidolonRSModule` 的真实结构，`ArsNouveauRSModule implements IModIntegration`
须实现：`configFlag()`、`modId()`、`registerModType()`、`registerBindingTargets()`、
`registerRecipeHandler()`、`registerNetworkPackets()`、`initCommon()`。

两个独立 `ModType`（`ModType.register(id, recipePrefixes[], jeiCategories[], guiKeys[],
delegateSupplier)`）：

```text
ars_nouveau_imbuement  -> ArsImbuementBatchDelegate
ars_nouveau_apparatus  -> ArsApparatusBatchDelegate
```

⚠ 旧版遗漏的集成点（每一项都要落实）：

- `RSIntegrationConfig` 新增 `ENABLE_ARS_NOUVEAU` 开关（`configFlag()` 返回它）。
- `ModCraftNetworkHandlers.registerArsNouveau()` + `NetworkPacketIds` 新增包 ID。
- `ModType.configureJei(...)` 两个类别 + 对应 `gui.rs_integration.jei.*` lang key。
- `ArsNouveauRecipeHandler` 注册进 `ModRecipeHandlers`。
- i18n：`en_us`/`zh_cn` 补机器名、等待原因、缺源提示等键（含限频提示文案）。
- `verifyReleaseJar` 资源校验须纳入新增 lang key。

绑定验证必须检查：方块实体类型与维度、区块已加载、玩家保护权限、机器空闲、Apparatus
（及注魔基座变体）周围有足够可用基座、Source 能力可读且不据为他人正在进行的操作。

一个物理机器同一时刻只允许一个 RSI operation token。Apparatus/注魔的中央块与全部被占用
基座共享同一独占域。

## 8. Imbuement 执行生命周期（对齐 IBatchDelegate）

真实契约方法：`validateAndInit` → `getRequiredMaterials`/`getGraphSpecs` →
`tryStartWithMaterials`（链式）或 `tryStartSingleCraft` → `observeCraft`（返回
`CraftObservation(WAITING_FOR_START|WORKING|DONE|FAILED)`）→ `collectResult`/
`collectAllResults` → `onBatchFinished`/`onBatchFailed`；`getMachinePos` 必实现；
机器槽产物应设 `getExpectedProduction`，中央槽类型 `getExpectedOutput` 返 null（不参与
world 磁铁拦截），`concurrencyCapabilities()` 返回 `machineSlot()`。

1. `validateAndInit` 读配方、输入、**基座料**、Source cost、槽状态。
2. `getRequiredMaterials` 汇报输入 + 基座料（供链式预留）；Source 不列入。
3. 预扣物品但机器接收前不结算 ledger。
4. `tryStartWithMaterials`：验证槽 0 空 + 基座就位，插入真实 NBT 输入（利用 `m_7013_`
   配方门控作为"已接受"探针，主线程调用）。
5. `observeCraft` 轮询：`recipe==null||!isMatch` → FAILED/WAITING；有配方但
   `slot0!=output` → WORKING；`slot0` 物品类型 == `getResult(tile)` 且异于输入 → DONE。
   ⚠ 不得用"槽非空"判 DONE；计入 100 tick 下限与软节流累源时间做超时。
6. `collectResult` 从真实槽提取结果交给 `NodeOutputAccumulator`；不按模板补发。
7. `onBatchFinished` 释放 operation lease。
8. 中止：机器未启动则取回输入 + 基座料并退款；已启动但状态未知则 draining，直到能证明
   结果或可恢复输入，不能同时退款并交付结果。⚠ 注意 `m_8016_` 无守卫——draining 期间要
   容忍外部已抽走输入的情形（判定为输入丢失而非产物）。

## 9. Apparatus 执行生命周期

1. `validateAndInit`：捕获并排序 `pedestalList()`（半径 1）生成稳定 pedestal layout，
   读配方（用 §5.1 精确类型判定，排除 enchantment/spell_write/armor/reactive 子类型）、
   Source cost、槽占用。
2. `getRequiredMaterials`：中央 reagent + 各基座 ingredient（保留 NBT）。
3. `tryStartWithMaterials`：占 lease → 放周边基座料 → 最后放中央输入 → 调 Ars 自身
   `attemptCraft(catalyst, null)`（反射，player 传 null 合法）。⚠ 不模拟 `assemble()`。
   放置顺序保证失败可逆。
4. `observeCraft`：读 `isCrafting`(public bool) 与 `counter`(private int，反射)。
   `isCrafting && counter<=210` → WORKING；`!isCrafting && counter==0 且中央槽出现异于
   catalyst 的结果` → DONE。⚠ **`isCrafting` 期间 `m_8020_` 返回空栈**，不要据此判 DONE。
5. `collectResult`：从中央槽提取真实结果；`clearItems()` 时基座剩余物走
   `getCraftingRemainingItem()` 回收（CONTAINER_RETURNING）。
6. 中止或启动拒绝：按槽逐一回收（中央 + 各基座），之后才允许 ledger 退款。

## 10. Source 与并发（修正）

- Source 读数只能作为启动前快照，不保证后续充足。
- ⚠ **无法证明 Source 预留原子性**（`takeSourceNearby` 无锁、逐个 `removeSource`）。
  旧版"除非能证明预留原子性否则不并行"实际等价于"**永不并行**同一 Source 网络上的多台
  Apparatus"——直接采用**串行**策略：同一 Source 网络内 Apparatus 同一时刻仅一个
  operation。
- Imbuement 的软节流特性（附近无源仍 `addSource(10)`）意味着缺源不是可行性问题；
  Imbuement 可按物理机器并行，每台机器同时一个 operation。缺源只返回限频 WAITING
  提示（不每 tick 刷屏）。
- 所有等待都有超时和可本地化原因；超时不得自动复制或删除物品。
- 并发裁定对齐 `BatchConcurrencyCapabilities`：两台机器都用 `machineSlot()`
  （CHAIN_RESERVED / MACHINE_SLOT / SEPARABLE_OFFLINE / MACHINE_LOCAL / RETRY_SAFE）；
  Apparatus 的基座 offset 填入 `supportOffsets`，Source 网络串行由独占域保证。

## 11. 测试与验收

⚠ 单元测试须符合本仓库测试可达面：RS API 为 `compileOnly`、测试运行时缺失；需
`ItemStack` 的用例继承 `BootstrapTest`；物理退款守恒依赖 `ServerPlayer`+`INetwork`，
单测够不到——这些只能靠游戏内验收。

可单测（纯逻辑）：

- handler 从 4.12.6 recipe 类提取准确输入/基座料/输出/Source cost；
- §5.1 精确类型分类：普通 `enchanting_apparatus` 入选，四个 NBT 变换子类型被排除；
- Crush 随机产物、summon/dispel 实体产物不进索引；
- 催化剂批量数量恒定、消耗随 executions 放大；
- Apparatus/注魔基座布局稳定、不重复占用同一基座。

游戏内验收（单测够不到的守恒/物理路径）：

- Imbuement 无基座 + 有基座变体、单次/批量/递归中间件都返回真实结果；
- Apparatus 单次/批量/递归返回真实结果，NBT 输入保留 Ars 生成的 NBT；
- 缺源时限频等待、不刷屏、不扣料；软节流下最终仍完成；
- 合成中断、区块卸载、玩家下线后材料守恒；⚠ 特别验证 `m_8016_` 无守卫场景：外部漏斗/
  磁铁抽走注魔输入时判为输入丢失，RSI 不同时退款并交付；
- operation settle/refund 恰好一次；
- 配方树数量、总需求条、实际预扣一致。

## 12. 完成定义

对应机器标记 supported 需全部满足：

- 显式 recipe handler 与 delegate 均存在，且 delegate 实现 `IBatchDelegate` 全部必需
  方法（`validateAndInit`/`tryStartSingleCraft`/`isCraftComplete`/`collectResult`/
  `onBatchFailed`/`onBatchFinished`/`getMachinePos`）；
- 真实机器生命周期测试通过（含 `observeCraft` 四态、`isCrafting`/`counter` 检测）；
- Source 不被错误物品化，并发按 §10 串行/并行策略；
- 催化剂与剩余物在成功、失败、中止路径守恒；
- §7 集成点（config/network/JEI/i18n/RecipeHandler 注册）全部落实；
- 完整测试与 `verifyReleaseJar` 通过；
- 分类不使用 recipe-ID 特判（用 `RecipeType`/精确类名，§5.1）。

