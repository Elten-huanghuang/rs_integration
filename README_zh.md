# RS Integration

[Refined Storage](https://www.curseforge.com/minecraft/mc-mods/refined-storage) 与各类模组机器的深度集成，支持按需远程合成、远程 GUI 访问、容器一键传输。

## 功能

- **机器绑定** — 手持 RS 网络连接器潜行右键任意支持的机器完成绑定，再次潜行右键可解绑。方块被破坏时自动清理残留绑定记录。
- **远程合成** — 在 JEI 中点击 `+` 按钮，通过已绑定机器直接触发合成，支持递归原料解析与 OR 树替代方案
- **合成计划界面** — 可视化合成步骤树：支持卡片视图和可缩放/可拖拽的节点图视图，子树折叠、替换配方选择、需求量标注、材料可用性彩色编码
- **负载均衡** — 绑定多台同类型机器时，自动将合成任务分配到所有空闲机器并行执行
- **合成链控制** — `/rsi cancel` 取消正在执行的合成链；聊天消息中的 `[取消合成]` 按钮一键中止
- **JEI 框选与快捷操作** — 在 JEI 物品列表中拖拽框选批量收藏/隐藏（A/H/Escape）；Alt+左键按模组筛选；Alt+中键清空搜索；Ctrl+T 将配方传输到 RS 合成终端
- **RS Grid 滑动提取** — 按住 Ctrl 在 RS 终端格子上滑动鼠标，每个滑过的物品提取一个，统一投递到玩家背包
- **自动进食** — 从 RS 网络自动消耗食物维持饱食度。三种模式：多样性（优先吃未吃过的）、堆叠（集中吃一种）、膳食（均衡营养）。支持拼音搜索的黑名单配置界面
- **远程 GUI** — 从 RS 终端侧面板远程打开已绑定机器的 GUI。关闭机器 GUI 后自动返回 RS 终端（可配置开关）。
- **侧面板** — 可折叠、可拖拽的浮窗，在任意界面上显示 RS 网络库存，配有快捷机器标签页
- **容器传输** — 打开任意容器 GUI 时按 `F` 一键将容器内物品全部转入 RS 网络；`G` 切换传输目标（RS 网络 / 精妙背包）。JEI/EMI/REI 搜索框获得焦点时自动屏蔽
- **次元升级** — Sophisticated Backpacks 现有升级（磁铁、拾取、喂食、补货、重存、存款）重定向到 RS 网络，从 RS 网络而非背包自身库存交互物品
- **绑定提示** — 手持已绑定物品时长按 Shift 可查看完整绑定机器列表（含维度名），支持资源包通过 `dimension.<namespace>.<path>` 覆盖翻译
- **机器管理中心** — 当绑定机器数量超过阈值时，RS 终端侧边出现机器管理中心按钮。点击打开可拖拽、可搜索（拼音）的机器网格浮窗，展示实时状态（空闲/工作中/产物就绪）、左键取产物/放物品/开 GUI、数字键快捷选择
- **天华砧 HUD** — 注视以太锻砧时，在准星旁显示热量、温度、余烬、拉杆绑定状态、自动锤炼状态的实时浮窗
- **共振磁盘** — 一种特殊的 RS 存储磁盘，盘内物品的被动效果（属性修饰符、inventoryTick 模拟、事件驱动重定向）可以像在玩家背包里一样生效
- **共振背包** — 便携式 GUI，用于浏览和管理共振磁盘内容，通过 RS 终端侧面板按钮打开

## 依赖

| 模组 | 必需 | 说明 |
|---|---|---|
| Forge | 是 | 47+（Minecraft 1.20.1） |
| Refined Storage | 是 | 1.12+ |
| JEI | 推荐 | 启用配方上的 `+` 按钮和远程合成计划 |
| Curios | 可选 | 支持 Curios 饰品栏中背包的检测 |
| Sophisticated Backpacks | 可选 | 背包传输、存款升级 RS 集成 |
| Sophisticated Core | 可选 | 使用 Sophisticated Backpacks 时必需 |
| 神秘遗物 | 可选 | 共振磁盘被动物品效果支持 |
| 神秘遗物拓展 | 可选 | 共振磁盘被动物品效果支持 |
| 月石 | 可选 | 共振磁盘九剑书支持 |
| 勇者传 | 可选 | 共振磁盘九剑书兼容支持 |
| 圣遗物 | 可选 | 共振磁盘消耗重定向支持 |

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
| **蟹农乐事**     | 捕蟹笼等海产加工设备 | ✅ | ✅ | ✅ |
| **农夫乐事**     | 烹饪锅、煎锅 | ✅ | ✅ | ❌ |
| **农夫暇事**     | 茶壶 | ✅ | ✅ | ❌ |
| **妖怪归家**     | 烹饪锅、蒸笼、摩卡壶、水壶、发酵罐、料理盘 | ✅ | ✅ | ❌ |
| **不死者乐事**   | 附魔冷却器 | ✅ | ✅ | ❌ |
| **汇流**         | 工坊 | ✅ | ✅ | ✅ |
| **神化**       | 重铸台 | ✅ | ❌ | ✅ |
| **远古神化**     | 古代重铸台 | ✅ | ❌ | ✅ |
| **PGP**      | PGP 枪械工作台 | ✅ | ❌ | ✅ |
| **EMX Arms** | EMX 武器工作台 | ✅ | ❌ | ✅ |
| **神秘遗物** | 被动物品效果（通过共振磁盘） | ➖ | ➖ | ➖ |
| **神秘遗物拓展** | 被动物品效果（通过共振磁盘） | ➖ | ➖ | ➖ |
| **月石** | 九剑书（通过共振磁盘） | ➖ | ➖ | ➖ |
| **勇者传** | 九剑书兼容（通过共振磁盘） | ➖ | ➖ | ➖ |
| **圣遗物** | 纵火者法杖消耗（通过共振磁盘） | ➖ | ➖ | ➖ |
| **禁忌与奥秘** | 光谱护符（通过共振磁盘） | ➖ | ➖ | ➖ |

> ¹ Eidolon 远程 GUI 仅工作台支持。  
> ² 禁忌与奥秘的 `apply_*_modifier` 配方需先绑定锻造台，通过 JEI `+` 打开锻造台 GUI 并自动填充模板与附加材料。

**自定义 GUI 机器** — 任意带有容器 GUI 的模组机器（如金属桶、蟹农乐事/捕蟹笼、PGP、EMX Arms、神化、TerraCurio 等）可通过配置中的 `customGuiMachineMods` 添加远程 GUI 支持，无需编写 Java 代码。默认已包含 `crabbersdelight`、`metalbarrels`、`pgp`、`emxarms`、`apotheosis`、`ancientreforging`。需手持网络连接器手动潜行右键绑定方可出现在面板中。

## 快速上手

1. 搭建 RS 网络（控制器、磁盘驱动器、终端）
2. 从 RS Addons 合成**网络连接器**
3. 手持连接器潜行右键任意支持的机器完成绑定（再次潜行右键解绑）
4. 打开 RS 终端 — 已绑定机器出现在侧面板的机器标签页中
5. 在 JEI 中点击配方上的 `+` 按钮，选择目标机器即可远程合成
6. 使用 `/rsi cancel` 取消正在执行的合成链，或点击聊天中的 `[取消合成]` 按钮
7. 在任意容器界面按 `F` 将物品一键转入 RS 网络，`G` 切换传输目标模式

## 合成流程

点击 JEI `+` 后，系统会：

1. **预览阶段** — 递归解析所有中间原料，生成完整的合成步骤树，在 `合成计划` 面板中展示 OR 替代路径
2. **执行阶段** — 从 RS 网络扣除材料 → 真实插入机器 → 等待加工完成 → 取出成品返回 RS

对于无 GUI 的机器（祭坛、火盆等），合成通过模组的配方 API 直接产出。对于有 GUI 的机器（熔炉、锅釜等），合成会物理插入物品到机器的输入槽，等待加工完成后取出。

## 按键绑定

| 按键 | 场景 | 功能 |
|---|---|---|
| `F`（默认） | 任意容器 GUI | 一键将容器中所有物品转入 RS 网络 |
| `G`（默认） | 任意容器 GUI | 切换传输目标模式（RS 网络 / 精妙背包） |
| 侧面板开关 | 任意界面 | 切换 RS 侧面板的显示/隐藏 |
| **Alt + 左键** | JEI 物品列表 | 按该物品所属模组筛选（等价于搜索框输入 `@modid`） |
| **Alt + 中键** | JEI 物品列表 | 清空 JEI 搜索框 |
| **Ctrl + T** | JEI 配方界面 | 将当前配方传输到 RS 合成终端 |
| **Ctrl + 左键拖拽** | RS 合成终端 | 滑动提取：滑过的每个格子提取一个物品 |
| 左键拖拽框选 | JEI 物品列表 / 书签面板 | 框选多个物品 |
| `A` | 框选后 | 批量收藏所有选中物品 |
| `H` | 框选后 | 批量隐藏所有选中物品 |
| `Escape` | 框选后 | 清除框选 |

## 自动进食

自动进食从 RS 网络自动消耗食物维持玩家饱食度。需要先获得配置中指定的药水效果（默认 `crockpot:gnaws_gift`，可改为空字符串取消此限制）。

### 三种模式

| 模式 | 行为 |
|---|---|
| **多样性（Diversity）** | 优先吃从未吃过的食物（需安装 SolCarrot） |
| **堆叠（Stack）** | 选定一种食物集中消耗 |
| **膳食（Diet）** | 根据营养均衡需求自动选择（需安装 Diet） |

### 黑名单配置

通过 `自动进食配置` 界面可为每种模式单独设置黑名单：
- 可搜索、可分页的食物网格，支持**拼音 / 拼音首字母**搜索
- 拖拽框选批量操作多个食物
- 黑名单在客户端和服务器之间自动同步

## JEI 框选

在 JEI 物品列表或书签面板中，**直接拖拽鼠标**即可框选多个物品：

- 框选结束后按 `A` — 批量收藏所有选中物品
- 框选结束后按 `H` — 批量隐藏所有选中物品
- 框选结束后按 `Escape` — 清除框选
- 搜索框获得焦点时框选自动禁用，避免按键冲突

## 合成计划界面

点击 JEI `+` 按钮后首先进入预览阶段，展示完整的合成步骤树：

- **卡片视图** — 线性步骤列表，清晰展示每一步的材料与产物
- **树视图** — 可缩放、可拖拽的节点图（DAG），展示配方依赖关系
- 支持**子树折叠**、**替换配方下拉选择**、**需求量标注**
- 材料可用性**彩色编码**：绿色（充足）/ 橙色（部分）/ 红色（缺失）
- 树视图中可直接 Ctrl+T 将任意子树对应的 JEI 配方传输到合成终端

## 负载均衡

当同一模组类型的多台机器均已绑定时，远程合成分发会自动使用所有空闲机器并行执行：

- 每轮分发前检查机器区块是否加载、BE 是否存在
- 多机并行时，材料预扣流程在链层面统一完成，确保中间产物（虚拟库存）对后续步骤可见
- 部分子机器启动失败不影响已成功的机器

## 机器管理中心

当绑定机器数量超过配置阈值时，RS 终端侧边出现机器管理中心按钮，点击打开可拖拽浮窗：

- **实时状态** — 每台机器显示当前状态：空闲（绿色）、工作中（黄色）、产物就绪（蓝色）
- **快捷操作** — 左键取产物/放物品/开 GUI；Shift+左键投递产物到 RS 网络；右键始终打开机器 GUI
- **数字键 1-9** 快捷选中对应机器
- **拼音搜索** — 支持拼音或首字母筛选机器名称
- **滚轮翻页**、**拖拽移动**浮窗位置
- `Escape` 先清除筛选文字，再次按下关闭浮窗

## 次元升级

Sophisticated Backpacks 现有升级，通过 NBT 绑定 RS Grid 后，将行为重定向到 RS 网络而非背包自身库存：

| 升级 | 功能 |
|---|---|
| **磁铁升级（RS）** | 将拾取的物品吸入 RS 网络 |
| **拾取升级（RS）** | 自动拾取范围内的物品存入 RS 网络 |
| **喂食升级（RS）** | 从 RS 网络自动消耗食物 |
| **补货升级（RS）** | 从 RS 网络补充快捷栏消耗物品 |
| **重存升级（RS）** | 从 RS 网络补满身上物品 |
| **存款升级（RS）** | 对准 RS Grid 时将背包物品存入 RS 网络（需开启配置 `depositUpgradeRS`） |
| **压制升级（RS）** | 从 RS 网络自动合并可压缩物品，同时支持 Majrusz's Accessories 饰品自动合成 |


## 余烬炼金计算模式

除了默认的"推断模式"（试错法），还提供确定性计算模式：

- 基于炼金术编码系统（aspect-position 推断），预先计算精确的基座布局
- 可配置最大尝试次数、连续零黑针上限、炼金台锁定超时、进度等待超时

## 调试命令

`/rsi_debug`（权限等级 2）提供以下子命令：

| 子命令 | 功能 |
|---|---|
| `dump chain` | 导出当前合成链状态 |
| `dump ledger` | 导出提取台账详情 |
| `dump index` | 导出配方索引统计数据 |
| `dump handlers` | 列出所有已注册的 Mod 配方处理器 |
| `dump bindings` | 导出玩家背包中的绑定信息 |
| `trace <物品>` | 对该物品进行候选决策与解析追踪（支持 `--no-inventory` 标志） |
| `audit` | 检查模组配方覆盖率 |
| `embers_clearcache` | 清除余烬炼金推断缓存 |
| `embers_clearlocks` | 清除余烬炼金台锁定 |
| `perf` | 导出性能监控快照 |

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
enableFarmersDelight = true
enableFarmersRespite = true
enableYoukaisHomecoming = true
enableImmortalsDelight = true
enableConfluence = true

[autoCrafting]
enableMultiblockAutoCrafting = true    # 允许多方块机器作为中间合成步骤

[sophisticated_backpacks]
depositUpgradeRS = false               # 背包存款升级与 RS 网络互通

[passiveEffects]
enableRSPassiveEffects = true           # RS 被动效果系统总开关
passiveTickItems = [...]                # 需从磁盘模拟 inventoryTick 的物品列表
nineSwordMaxCount = 9                   # 九剑书最大有效剑数上限

[containerTransfer]
enableContainerTransfer = true

[autoEat]
requiredEffect = "crockpot:gnaws_gift"     # 自动进食需要的药水效果（空字符串 = 无需效果）
perItemCost = 1                            # 每次进食从网络中消耗的物品数量

[sidePanel]
enableRSSidePanel = true

[remoteMachineGui]
enableMachineGuiTabs = true
customGuiMachineMods = ["crabbersdelight", "metalbarrels", "pgp", "emxarms", "apotheosis", "ancientreforging"]

[advanced]
diagnosticVerboseLogging = false
containerTransferKey = 70                   # 容器传输快捷键（默认 G = 70）
```

### 服务器配置（`saves/<世界名>/serverconfig/rs_integration-server.toml`）

```toml
[integrations]
crockpotFillerItem = "minecraft:stick"    # 锅釜空余格位的填充物
embersInferMaxAttempts = 20               # 余烬炼金推断模式最大尝试次数
embersInferZeroBlackLimit = 5             # 连续零黑针上限
embersLockTimeoutMinutes = 10             # 炼金台锁定超时（分钟）
embersProgressTimeoutTicks = 600          # 炼金台等待进度超时（刻）
embersCalculateSafeMode = false           # 计算模式安全模式（仅使用已知 aspect，不从空针推断）
embersCalculateMaxLayouts = 256           # 计算模式最大布局数

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
