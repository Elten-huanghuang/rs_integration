# RS Integration

[Refined Storage](https://www.curseforge.com/minecraft/mc-mods/refined-storage) 与各类模组机器的深度集成，支持按需远程合成、远程 GUI 访问、容器一键传输。

## 功能

- **机器绑定** — 手持 RS 网络连接器潜行右键任意支持的机器完成绑定，再次潜行右键可解绑。方块被破坏时自动清理残留绑定记录。
- **远程合成** — 在 JEI 中点击 `+` 按钮，通过已绑定机器直接触发合成，支持递归原料解析与 OR 树替代方案
- **合成链控制** — `/rsi cancel` 取消正在执行的合成链；聊天消息中的 `[取消合成]` 按钮一键中止
- **远程 GUI** — 从 RS 终端侧面板远程打开已绑定机器的 GUI。关闭机器 GUI 后自动返回 RS 终端（可配置开关）。
- **侧面板** — 可折叠、可拖拽的浮窗，在任意界面上显示 RS 网络库存，配有快捷机器标签页
- **机器中枢** — 当绑定机器数量超过阈值时，将独立标签页折叠为可搜索的网格浮窗，风格统一匹配 RS 终端主题
- **容器传输** — 在任意容器 GUI 中一键将物品倒入 RS 网络（支持 Sophisticated Backpacks 存款过滤器判定）
- **绑定提示** — 手持已绑定物品时长按 Shift 可查看完整绑定机器列表（含维度名），支持资源包通过 `dimension.<namespace>.<path>` 覆盖翻译
- **天华砧 HUD** — 注视以太锻砧时，在准星旁显示热量、温度、余烬、拉杆绑定状态、自动锤炼状态的实时浮窗

## 依赖

| 模组 | 必需 | 说明 |
|---|---|---|
| Forge | 是 | 47+（Minecraft 1.20.1） |
| Refined Storage | 是 | 1.12+ |
| JEI | 推荐 | 启用配方上的 `+` 按钮和远程合成计划 |
| Curios | 可选 | 支持 Curios 饰品栏中背包的检测 |
| Sophisticated Backpacks | 可选 | 背包传输、存款升级 RS 集成 |
| Sophisticated Core | 可选 | 使用 Sophisticated Backpacks 时必需 |

## 已支持的模组

| 模组           | 机器 | 绑定 | 合成 | 远程 GUI |
|--------------|---|---|---|---|
| **原版**       | 熔炉、高炉、烟熏炉、切石机、锻造台、铁砧、附魔台 | ✅ | ✅ | ✅ |
| **Goety**    | 死灵火盆、黑暗祭坛、诅咒笼、灵魂烛台 | ✅ | ✅ | ❌ |
| **Malum**    | 灵魂祭坛、灵魂熔炉（核心 + 组件） | ✅ | ✅ | ❌ |
| **Eidolon**  | 工作台、坩埚、火盆 | ✅ | ✅ | ⚠️¹ |
| **禁忌与奥秘**    | 赫菲斯托斯锻炉、锻造台² | ✅ | ✅ | ❌ |
| **巫师重生**     | 知识结晶器、奥术迭代器、奥术工作台、水晶方块 | ✅ | ✅ | ❌ |
| **车万女仆**     | 女仆祭坛 | ✅ | ✅ | ❌ |
| **余烬**       | 炼金台 | ✅ | ✅ | ❌ |
| **以太工坊**     | 以太锻砧（世界内锤打合成） | ✅ | ✅ | ❌ |
| **天境**       | 冷冻器、孵化器、祭坛 | ✅ | ✅ | ✅ |
| **锅釜**       | 大锅、便携锅 | ✅ | ✅ | ✅ |
| **TACZ**     | 枪械工作台 A/B/C | ✅ | ✅ | ✅ |
| **农贸市场**     | 市场 | ✅ | ✅ | ✅ |
| **拔刀剑**      | 工作台配方（NBT 判定） | ➖ | ✅ | ➖ |
| **无尽贪婪**     | 导向工作台、中子态素压缩机、极致锻造台 | ✅ | ✅ | ✅ |
| **金属桶**      | 铁桶、金桶、钻石桶等各级金属桶 | ✅ | ❌ | ✅ |
| **蟹农乐事**     | 捕蟹笼等海产加工设备 | ✅ | ❌ | ✅ |
| **汇流**       | 工坊 | ✅ | ❌ | ✅ |
| **神化**       | 重铸台 | ✅ | ❌ | ✅ |
| **远古神化**     | 古代重铸台 | ✅ | ❌ | ✅ |
| **PGP**      | PGP 枪械工作台 | ✅ | ❌ | ✅ |
| **EMX Arms** | EMX 武器工作台 | ✅ | ❌ | ✅ |

> ¹ Eidolon 远程 GUI 仅工作台支持。  
> ² 禁忌与奥秘的 `apply_*_modifier` 配方需先绑定锻造台，通过 JEI `+` 打开锻造台 GUI 并自动填充模板与附加材料。

**自定义 GUI 机器** — 任意带有容器 GUI 的模组机器（如金属桶、蟹农乐事/捕蟹笼、PGP、EMX Arms、神化、TerraCurio 等）可通过配置中的 `customGuiMachineMods` 添加远程 GUI 支持，无需编写 Java 代码。默认已包含 `crabbersdelight`、`farmingforblockheads`、`metalbarrels`、`pgp`、`emxarms`、`apotheosis`、`confluence`。需手持网络连接器手动潜行右键绑定方可出现在面板中。

## 快速上手

1. 搭建 RS 网络（控制器、磁盘驱动器、终端）
2. 从 RS Addons 合成**网络连接器**
3. 手持连接器潜行右键任意支持的机器完成绑定（再次潜行右键解绑）
4. 打开 RS 终端 — 已绑定机器出现在侧面板的机器标签页中
5. 在 JEI 中点击配方上的 `+` 按钮，选择目标机器即可远程合成
6. 使用 `/rsi cancel` 取消正在执行的合成链，或点击聊天中的 `[取消合成]` 按钮
7. 在任意容器界面按容器传输快捷键（默认 `G`）将物品倒入 RS 网络

## 合成流程

点击 JEI `+` 后，系统会：

1. **预览阶段** — 递归解析所有中间原料，生成完整的合成步骤树，在 `合成计划` 面板中展示 OR 替代路径
2. **执行阶段** — 从 RS 网络扣除材料 → 真实插入机器 → 等待加工完成 → 取出成品返回 RS

对于无 GUI 的机器（祭坛、火盆等），合成通过模组的配方 API 直接产出。对于有 GUI 的机器（熔炉、锅釜等），合成会物理插入物品到机器的输入槽，等待加工完成后取出。

## 配置

所有配置文件均为 TOML 格式，服务器配置会自动同步至客户端。

### 通用配置（`config/rs_integration-common.toml`）

```toml
[general]
enableBinding = true            # 机器绑定总开关
enableAutoCrafting = true       # 递归自动合成总开关

[integrations]
enableGoety = true
enableMalum = true
enableWizardsReborn = true
enableForbiddenArcanus = true
enableEidolon = true
enableTouhouLittleMaid = true
enableSlashblade = true
enableAvaritia = true
enableAetherworks = true
enableAether = true
enableCrockPot = true
enableTacz = true
enableEmbersAlchemy = true
enableEmbersAlchemyCalculate = false   # 余烬炼金确定性计算模式
enableVanillaMachines = true
enableSophisticatedBackpacks = true
enableJeiIntegration = true
enableFarmingForBlockheads = true

[autoCrafting]
enableMultiblockAutoCrafting = true    # 允许多方块机器作为中间合成步骤

[sophisticated_backpacks]
depositUpgradeRS = false               # 背包存款升级与 RS 网络互通

[containerTransfer]
enableContainerTransfer = true

[sidePanel]
enableRSSidePanel = true

[remoteMachineGui]
enableMachineGuiTabs = true
customGuiMachineMods = ["crabbersdelight", "farmingforblockheads", "metalbarrels", "pgp", "emxarms", "apotheosis", "confluence", "ancientreforging"]

[advanced]
diagnosticVerboseLogging = false
```

### 服务器配置（`saves/<世界名>/serverconfig/rs_integration-server.toml`）

```toml
[integrations]
crockpotFillerItem = "minecraft:stick"    # 锅釜空余格位的填充物
embersInferMaxAttempts = 20               # 余烬炼金推断模式最大尝试次数
embersInferZeroBlackLimit = 5             # 连续零黑针上限
embersLockTimeoutMinutes = 10             # 炼金台锁定超时（分钟）
embersProgressTimeoutTicks = 600          # 炼金台等待进度超时（刻）

[autoCrafting]
preferredRecipes = []                     # 优选配方 ID（冲突时 +10000 分值加成）
multiblockCraftTimeoutSeconds = 300       # 多方块合成超时（秒）
multiblockRecipeBlacklist = []            # 多方块配方黑名单
multiblockRecipeAllowlist = []            # 多方块配方白名单（空 = 全部允许）
repeatCountMax = 64                       # 合成计划最大重复次数
craftingMaxDepth = 8                      # 递归合成最大深度
craftingMaxSteps = 4096                   # 单次解析最大步骤数
protectedItems = []                       # 合成时需要保留最低存量的物品
protectedReserve = 2                      # 保留的最低份数
```

### 客户端配置（`config/rs_integration-client.toml`）

```toml
[sidePanel]
rsSidePanelX = 0
rsSidePanelY = 0
rsSidePanelWidth = 200
rsSidePanelHeight = 160
rsSidePanelHidden = false
```

## 许可证

All rights reserved.
