<p align="center">
  <img src="src/main/resources/rs_integration_logo.png" width="96" alt="RS Integration Logo">
</p>

<h1 align="center">RS Integration</h1>

<p align="center">
  <a href="README.md">English</a> | <strong>简体中文</strong>
</p>
<p align="center"><strong>Minecraft 1.20.1 · Forge 47+ </strong></p>

RS Integration（RSI）为 Refined Storage 和 Beyond Dimensions 增加递归合成、机器远程访问、存储联动及可选魔法资源集成。它是附属模组，不替代两种存储网络。

**当前版本：1.5.2 | Minecraft 1.20.1**

[1.5.2 更新公告](docs/RELEASE_NOTES_1.5.2.md)

[MC百科介绍](https://www.mcmod.cn/class/29199.html)

## 目录

[运行要求](#运行要求) · [主要功能](#主要功能) · [快速开始](#快速开始) · [按键](#按键) · [模组兼容](#模组兼容) · [开发者 API](#开发者-api) · [配置](#配置) · [诊断](#诊断) · [构建](#构建)

## 运行要求

| 依赖 | 要求 |
|---|---|
| Minecraft / Forge | 1.20.1 / 47+ |
| Refined Storage | 1.12+，可选；未安装时仍可使用玩家背包递归合成等功能 |
| JEI | 客户端必需，用于配方操作和计划预览 |
| 其他集成模组 | 可选；只有安装目标模组时才加载对应模块 |

## 主要功能

### 递归合成

- 从 JEI/EMI 启动合成，先查看配方图、步骤、现有/缺少材料、替代配方、概率估算和重复次数，再决定是否开始。
- 材料来源包括玩家背包以及分别解析的 RS、BD 网络；会沿工作台、模组机器、交易和纯背包配方递归合成，但不会把 RS 与 BD 余额相加。
- 支持替代配方、概率材料补做、多产物、副产物、容器返还和流体容器配方；支持的机器可输入预载与批量处理，减少反复派发。
- DAG 调度、材料预留、机器/捕获租约、输入缓存和产物结算会在取消、机器卸载、玩家断线和失败时维持物料守恒。
- 支持时返还产物、容器和未用完材料；无法放入网络的流体会转为退款物品，背包满或离线后可用 `/rsi_fluid_refund` 领取。离线合成产生的普通物品退款会随世界保存，登录时自动领取，也可使用 `/rsi_item_refund` 补领。
- 按 `P` 查看进度，可在进度界面或输入 `/rsi cancel` 取消合成链。

### 工作台式即时批量

| 执行契约 | 当前接入 | 执行边界 |
|---|---|---|
| 原版稳定批处理 | 工作台的有序/无序配方、切石机配方 | 真实执行 `assemble/getRemainingItems`；稳定配方一次代表剩余执行次数，结果按 `Integer.MAX_VALUE` 分片 |
| 有界真实装配 | SlashBlade 工作台配方、Apotheosis 制箭台/宝石切割台、Iron's Spellbooks 卷轴撰写台/奥术铁砧、Apprentice Codex 施法者工作台、ISS CSW 法术锻炉、Eidolon 工作台、Biomancy 生物锻炉、符合条件的 Avaritia 压缩/极限工作台、Malum 符文工作台 | 没有原生加工 tick，但仍需真实装配或机器状态；单批最多 64 次，超过后继续下一批 |
| 直接账本结算 | RSI 流体容器转换、Lychee 确定性物品浸泡、Immortaler's Delight 温泉、Malum 虚空恩惠和 Farming for Blockheads 市场 | 处理器显式声明成本与产物确定；一个操作可代表剩余执行次数，`craftingVirtualExchangeBatchLimit=0` 时不额外限制，非零值才设置批上限 |

超过单个 `ItemStack.getCount()` 上限的产物会拆成多个真实堆叠，不会静默饱和或丢弃。

这些路径必须由 `InstantRecipePolicy` 显式选择；机器看起来是“秒出”并不会仅凭名称或 tick 速度被提升为直接倍增。无损催化剂和复用工具继续使用原有材料角色：每个 worker 或批次只预留一次，成功结算后原样返还。

批量拆分、机器输入预载和多机器并行仍是三项独立能力。EAEP 式 `64 + 64 + 余数` 决定请求如何切片，`InputBufferContract` 决定一台实体机器能预装多少，`BatchConcurrencyCapabilities` 决定多个操作能否并发。

### 机器远程访问

- 使用网络连接器、`Alt` + 右键或 `;` 附近扫描绑定机器；机器拆除后自动清理失效绑定。
- 终端侧边按钮只浏览有 GUI 的机器，并可直接打开；`P` 控制中心显示全部绑定机器，右上角 `X` 解除绑定，右键切换是否参与自动合成。收藏的 GUI 机器继续显示在终端快捷栏。
- 多台兼容机器可以分担重复任务；单机操作上限和租约避免一台机器同时执行冲突任务。
- 自定义 GUI 绑定可远程打开不参与配方自动化的机器。
- 遵守 FTB Chunks/Cadmus 领地权限，不会强制加载远处区块，并会限制远程界面请求频率、重新校验每次服务端操作。

### 共振容器

- 共振魔力池继承 Botania 原生 `ManaPoolBlockEntity`，保留丢入物品手动灌注、火花、接收器、输出模式和原生魔力液面渲染。
- 共振魔源罐继承 Ars Nouveau 原生 `SourceJarTile`，中继器、分流器及魔源消费者走原生接口，不需要逐个配方打补丁。

### 万界归墟盘

- 同一张磁盘可保存物品、流体和魔法资源，并直接接入原版 RS 磁盘仓；物品种类、流体种类、单条目数据和整盘载荷上限均可配置。
- 每个精确物品或流体条目最高保存 `Long.MAX_VALUE`；RS 原生的 `ItemStack`、`FluidStack` 和 `IStorageDisk` 操作仍按 `int` 分片，RSI 会在混合终端和合成快照中补回完整数量，不会与原生缓存重复计算。
- `MANA`、`SOURCE` 和 `SOUL` 保持独立资源行，不互相换算；`MANA` 与 `SOUL` 可进入 RSI 递归合成，`SOURCE` 保留 Ars Nouveau 原生传输。
- 魔法资源存档使用 `long`，旧版 12 字节 `int` 记录可直接读取；物品与流体数量页也改为 `long`，并兼容旧版 4 字节数量页。
- 混合终端中的物品、流体和魔法数量，以及配方树标签、墨水数量和共振容器 HUD，统一使用 `K/M/G/T/P/E` 压缩显示，直到 `Long.MAX_VALUE` 都不会撑出格子；tooltip 仍保留完整数值。
- 保留 NBT 敏感物品身份，可参与存储缓存、自动化设备、混合终端、JEI/EMI 查询及物品/流体递归提取。
- 调高条目上限后旧盘会自动扩容；保存校验、异常 dirty 保留、掉落保护、虚空回收和按 UUID 的管理员找回降低丢盘风险。
- 缺失模组的物品或流体只会被隔离，正常库存继续可用；隔离条目保留原始数据、数量并继续占用容量。
- 存在对应配方时，无限容量 RS 磁盘可转化为万界归墟盘。
- RS 归墟盘与 BD 网络是两套独立库存，RSI 不会自动把两边余额相加。

### 共振存储盘

- RS 共振存储盘可让盘内物品提供属性、`inventoryTick`、事件驱动被动效果和物品/资源查询；受支持的状态变化会写回磁盘，而不是只模拟一次性副本。
- 被动效果由属性扫描、可配置 tick 模拟和事件钩子共同实现，覆盖药水护符、烈焰术士法杖、石英花、幽灵之眼护符、九剑书、Terra Curios 增益饰品和成组无限药水等物品。
- TaCZ 散装子弹会按口径参与换弹并保留 NBT 与真实数量；枪械 HUD 备弹包含盘内子弹，Shift 快速存入只有磁盘成功写入后才扣除背包物品。
- 细雪、原液、毒液、怨泉等资源可参与递归合成；通过井交互可获得 Malum 虚空恩惠及对应转化能力。
- 共振盘可打开 36 格共振背包界面，并防止被 RS 的 `Shift` + 右键快捷拆解；每个 RS 网络只启用一张共振盘。
- BD 共振网络发生器提供对应的盘式被动存储：右键绑定 BD 网络，必须随身携带，同网络多个发生器会在容量允许时合并。
- BD 发生器遵守网络权限并独立保存虚空恩惠；其内容保持为独立共振空间，不进入 BD `UnifiedStorage`。

### 其他模组组合集成

- **混合终端**：在同一列表中显示物品与流体，支持搜索筛选、JEI 传送及容器装液/倒液；容器无法接收时安全回滚。
- **流体容器配方**：独立显示装液、倒液和容器返还配方，执行前检查产物存储空间，并支持收藏与配方传送。
- **RS 终端模式**：可在工作台、切石机、锻造台和铁砧模式间切换；JEI 传送会跟随当前模式，不可用时回退原版 3×3 工作台。
- **配方操作**：支持的配方会显示 RSI 合成按钮；已连接机器还可能提供直接打开按钮。
- **JEI 专用页面**：提供 FTB Quests 物品提交、Distant Worlds Lithum 祭坛和 Project MMO 回收页面。
- **JEI 快捷操作**：可收藏缺少材料、按模组筛选、用 `Ctrl+T` 把配方传入 RS 网格，或在物品上按中键从网络取出一组。
- **侧边栏**：按 `Y` 打开或收起（配置默认关闭），无需关闭当前界面即可搜索存储和访问机器；按 `U`/`R` 查看用途或配方。
- **库存覆盖层**：显示网络库存数量和合成缺料；框选条目后可批量收藏或隐藏。
- **RS 搜索**：支持名称、模组（`@`）、标签（`$`）、提示文字（`#`）和拼音，并提供后台索引、最近搜索、收藏、删除及可配置的磁盘缓存。
- **终端快捷操作**：在 RS/BD 终端按 `F` 填入当前 JEI/EMI 物品；按住 `Ctrl` 拖过 RS 格子，每格取出一个物品。
- **Tetra 材料筛选**：加工台会筛选适合当前部件的材料；`Ctrl` 悬停可预览结果，也能按属性排序。
- **配置编辑器**：JEI 配置按钮可打开游戏内编辑器，按客户端、通用、服务端和存储分类搜索、校验、保存、恢复默认，并提示需要重启的选项。
- **机器自动化**：适配优先复用目标模组原生配方和传输路径，并且只在目标模组存在时加载。Embers 可解答并记住炼金台编码、运行熔炼炉/混合离心器/压印锤/微型锅炉；Aetherworks 支持自动锤炼、补料、控温及金属成型台物品/流体输入。
- **流体供给**：需要水的机器可从所选 RS 网络补水，`autoCrafting.freeWaterMachines` 决定哪些机器免费补水、哪些消耗网络流体。
- **仪式与机器模块**：Goety 只准备仪式不负责启动；Better Summoning Rituals 远程准备祭坛且不自动启动或收取世界掉落；Biomancy 支持消化器、生物实验室、分解器和生物锻炉；克苏鲁生物血肉祭坛支持带数量材料、批量投料和多祭坛。
- **Distant Worlds**：可配置研究跳过、关闭 Firon 随机失败、自动补充附近熔炉燃料，并设置搜索半径、单次补料上限和燃料优先级。
- **烹饪自动化**：Farmer's Delight、Farmer's Respite、Youkai's Homecoming、Dungeon's Delight、Twilight Delight、BOSSes' Delight、Casualness Delight、Farm & Charm、Candlelight、Bakery、Miner's Delight、Crock Pot 和 Gensokyo Delight 保留原生热源、容器、批量、流体及世界掉落规则。
- **HUD**：Aetherworks 显示锤炼、温度、余烬、补货和自动状态；Distant Worlds 显示祭坛、能量、配方和基座状态，可用 `distantWorlds.enableHud` 关闭；合成进度和绑定提示继续在世界内显示。
- **自动进食**：支持 SolCarrot 多样饮食、Diet 膳食均衡和堆叠暴食，可配置食物/状态效果黑名单、容器返还、使用条件、执行消耗和单次上限。
- **交易与补货**：按 `Space` 补充村民付款、附魔青金石、铁砧和 Apotheosis/Ancient Reforging 材料；缺少的交易物品会显示并加入 JEI 收藏。
- **Apotheosis**：附魔图书馆扫描并合并 RS 库存，执行服务端校验和安全退回；刷怪笼支持升级选择、材料规划、递归供料及 RS/BD 访问。
- **容器转移**：按 `F` 把容器内容转移到当前 RS/BD 网络，或用 `G` 选择的精妙背包；操作遵守过滤器、黑名单、容量、文字输入和原版 `F` 使用场景。
- **精妙背包**：磁铁、拾取、喂食、补货、存入和虚空升级可连接 RS；次元磁铁支持世界流体采集、JEI 液体过滤、幽灵槽筛选及容器/升级安全检查。
- **FTB Quests**：支持制作/存入进度、网络提交、缺料扫描、连续领取和可关闭的侧栏按钮；已有权威任务处理器优先。
- **自定义 GUI**：Crabber's Delight、Metal Barrels、PGP、EMX Arms、Apotheosis、Ancient Reforging、附魔台、Enchanting Infuser 系列和 Tetra 加工台可绑定并远程打开。
- **其他工具**：JEI 收藏保护交易，建筑手杖统计网络方块，RSI 虚空升级按规则销毁新放入物品，保留物品列表可保护最低库存，并继续适配 Miner's Delight、Terra Curio、History Stages 和 EMI 原生按钮位置。

## 快速开始

1. 建造或连接 RS 网络，并取得网络连接器。
2. 用 `Alt` + 右键绑定机器，或在机器附近按 `;` 扫描。
3. 在 JEI/EMI 打开配方并点击 RSI 合成操作。
4. 检查计划，必要时选择替代配方，然后启动。
5. 按 `P` 查看进度；没有存储网络时，产物会尽量退回玩家。

## 按键

所有按键都可以在 Minecraft 控制设置中修改。

| 按键 | 功能 |
|---|---|
| `F` | 转移容器内容，或把 JEI/EMI 选中物品填入 RS/BD 搜索框 |
| `G` | 在 RS 和精妙背包之间切换转移目标 |
| `Y` | 显示/隐藏 RS 侧边栏 |
| `P` | 显示/隐藏 RSI 控制中心，包含活动合成与绑定机器管理 |
| `;` | 扫描并绑定附近支持的机器 |
| `Alt` + 右键 | 把手持终端绑定到准星指向的机器 |
| `Alt` + 左键 | 按模组筛选 JEI 物品 |
| `Alt` + 中键 | 清空 JEI 物品搜索 |
| `Ctrl+T` | 把 JEI 配方传入 RS 合成网格 |
| `Ctrl` + 左键拖动 | 从每个 RS 格子取出一个物品 |
| 左键拖框后按 `A` / `H` / `Esc` | 收藏 / 隐藏 / 清除 JEI 框选 |
| `U` / `R` | 查看侧边栏悬停物品的用途 / 配方 |
| `Space` | 补充村民交易、青金石或铁砧/重铸材料 |

## 模组兼容

集成模块均在运行时按安装情况加载。JEI/EMI 使用目标模组自己的配方分类，并在支持时使用原生工作站入口。

<details>
<summary><strong>完整兼容表</strong></summary>

| 模组 | 支持的配方或机器 |
|---|---|
| JEI / EMI | 配方浏览器合成/机器按钮、配方传送、缺料收藏、Tetra 材料工具、网络提取，以及 FTB/Distant Worlds/PMMO 专用分类 |
| 苦旅 The Long Travail | 春之冠 JEI 变体会把当前选中花朵的 NBT 作为递归目标 |
| 原版 / 砖制熔炉 | 熔炉、高炉、烟熏炉、营火、炼药台、切石机、锻造台、铁砧、附魔台 |
| Iron Furnaces | 各等级熔炉的熔炼、烧炼和烟熏模式，包括工厂/彩虹熔炉整组批处理与多机器负载均衡 |
| Sophisticated Storage | 箱子升级配方 |
| Botania | 魔力池/催化器、花瓣药剂台、自动使用森林法杖的符文祭坛、植物酿造台、精灵交易、泰拉凝聚板、白雏菊 |
| Improved Botania Pools | Alfheim、Asgard、Muspelheim、Nilfheim 四种魔力池，支持植物魔法灌注配方、催化器、批处理和负载均衡 |
| MythicBotany | Mana Infuser（精灵凝聚板）配方，也支持整合包添加的兼容配方 |
| Ars Nouveau | 灌注室、附魔装置、抄写台魔符递归合成，以及复用受支持 Ars 配方类型的扩展配方；抄写台左右两半均可绑定 |
| Iron's Spells 'n Spellbooks | 卷轴撰写台、奥术铁砧的卷轴制作与升级；支持 Refined Mod、T.O Magic 'n Extras、Peyro's Scythe、GTBC's Geomancy Plus 等兼容附属卷轴 |
| Apprentice Codex | 精华熏炉、施法者工作台 |
| ISS CSW | 法术锻炉和使用多个卷轴的混合法术配方 |
| Goety / Goety Awaken | 注入器、火盆、祭坛、诅咒之笼、灵魂烛台和兼容仪式 |
| Better Summoning Rituals | 远程准备祭坛、带数量材料、催化剂、失败回滚和冲突回收；最终启动与世界掉落收取仍需手动 |
| Biomancy | 消化器、生物实验室、分解器和生物锻炉，支持营养/燃料、物品/流体输入、批处理及原生机器规则 |
| Cthulhu Creatures | 血肉祭坛配方，支持带数量材料、批量投料、多祭坛执行、远程界面和递归规划 |
| Malum | 精魂祭坛、精魂坩埚、符文工作台、精魂灌注及相关配方；相邻祭坛可分别收取产物，精魂催化器和符文木方尖碑的加速效果正常生效 |
| Eidolon | 工作台、坩埚、火盆 |
| Forbidden & Arcanus | 赫菲斯托斯锻炉、Clibano、锻造/词条应用流程 |
| Wizards Reborn | 秘蕴晶化器、奥术迭代器、工作台、水晶仪式 |
| Touhou Little Maid | 女仆祭坛，可从 RS 网络自动补充 P 点物品 |
| Wishing Fountain | 已建成许愿喷泉的绑定，以及群系/结构地图许愿；没有物品产出的天气许愿仍需手动完成 |
| Embers Rekindled | 炼金台、熔炼炉、混合离心器、压印锤、微型锅炉；炼金台支持自动试错猜测炼金编码、保存推断结果和确定性布局 |
| Aetherworks | 天华砧、锻造工具站、金属成型台；支持自动锤炼、自动补料、锻造炉拉杆控温，以及温度/余烬/锤击进度 HUD。金属成型台支持物品与 RS 流体递归供料，需连接完整锻造炉、提供余烬并清空物品槽和流体罐 |
| The Aether | 冷冻器、孵化器、祭坛 |
| Crock Pot | 锅釜、便携锅釜，以及鸟笼的鹦鹉喂养/产蛋配方 |
| Farmer's Delight | 烹饪锅、煎锅、切菜板，以及兼容的奥术炉配方 |
| Dungeon's Delight | 怪物锅配方，支持容器规划、批量预载及多台锅并发，保留原生怪物热源要求 |
| Farmer's Respite | 茶壶 |
| Youkai's Homecoming | 摩卡壶、发酵罐、单层或多层蒸笼、水壶、各类烹饪锅、料理台 |
| Twilight Delight | 炽铁锅，保留原生自供热行为和 Farmer's Delight 烹饪配方 |
| BOSSes' Delight | 灵魂锅，校验冰/浮冰/蓝冰冷源，支持容器处理与批量烹饪 |
| Casualness Delight | 炸锅，保留原生热源要求并捕获世界掉落成品 |
| Farm & Charm | 烹饪锅与烘焙机，保留原生热源、容器和批量规则 |
| Candlelight | 汤锅/铜汤锅，复用 Farm & Charm 烹饪配方并接受烛火晚宴炉灶热源 |
| Bakery | 小烹饪锅，复用 Farm & Charm 烹饪配方与热源规则 |
| Gensokyo Delight | 发酵罐、蒸笼、水壶、各类烹饪锅和料理台，兼容重命名后的字段结构 |
| Immortaler's Delight | 新旧版本怨泉及温泉灌注配方；怨泉桶资源可通过共振磁盘参与合成 |
| Apotheosis | 制箭、宝石切割、附魔图书馆、重铸台和刷怪笼升级 |
| TACZ 与兼容枪包 | 枪械工作台 |
| Avaritia | 六级压缩工作台、终极锻造台、中子态素收集器、压缩箱及相关机器 |
| Until Eternity | 终末工作台的 5×5 配方递归合成；共振盘内的「我吃饱了！」可触发原模组的饱食度与口渴保护，「活力紫水晶」可通过原模组逻辑赋予紫水晶祝福 |
| SlashBlade | 支持带 NBT 条件的拔刀剑工作台配方 |
| Confluence | 工坊 |
| Distant Worlds | Lithum 祭坛及相关交互；准星 HUD 显示配方、能量、恢复速度和八个基座状态 |
| Lychee | 物品浸泡和方块交互配方；方块交互配方可使用绑定液压机执行 |
| Farming for Blockheads | 市场交换，也可以作为合成步骤使用 |
| Project MMO | 概率回收配方 |
| Crabber's Delight | 捕蟹笼处理 |
| Tetra | 加工台远程界面、材料筛选、Ctrl 悬停结果预览和可配置的 JEI 材料排序 |
| Miner's Delight | 支持容器返还与批处理的铜锅配方 |
| Terra Curio | 工坊配方、绑定和容器转移保护 |
| FTB Teams / FTB Quests | 网络提交、物品任务进度、连续领取奖励和可关闭的任务侧栏按钮 |
| Beyond Dimensions | 另一种存储网络，可用于支持的合成、机器访问和 JEI 提取 |
| Curios | 饰品栏连接器查找、共振/被动效果联动和 FTB 奖励物品检测 |
| FTB Chunks / Cadmus | 领地感知的机器绑定与远程操作校验 |
| Sophisticated Backpacks | 远程界面/转移，磁铁/拾取/喂食/补货/存入/虚空升级，世界流体采集与 Majrusz 压缩 |
| Enchanting Infuser | 普通与高级附魔灌注器远程界面绑定 |
| History Stages | 配方规划遵守阶段锁定 |
| Construction Wand | 建造魔杖可使用网络库存中的方块 |
| Easy Villagers / Trade Cycling / Trade Refresh / Retraining | JEI 收藏可防止村民交易被刷新重置 |

</details>

自定义 GUI 默认包含 Crabber's Delight、Metal Barrels、PGP、EMX Arms、Apotheosis 和 Ancient Reforging，即使不自动制作配方也可绑定并远程打开。

## 开发者 API

RS 与 BD 使用独立 API，不共享库存，也不会自动合并余额。

RS 魔法资源入口：[`RsiMagicApi`](src/main/java/com/huanghuang/rsintegration/api/magic/RsiMagicApi.java)

```java
RsiMagicApi.Session session = RsiMagicApi.network(network);
int available = session.available(MagicResourceKind.SOUL);

try (MagicResourceNetworkPool pool =
             session.reserve(MagicResourceKind.SOUL, available)) {
    int extracted = pool.withdraw(available);
    // 使用提取出的灵魂能量。
}
```

BD 魔法资源入口：[`BdMagicApi`](src/main/java/com/huanghuang/rsintegration/api/magic/BdMagicApi.java)

```java
BdMagicApi.Session session = BdMagicApi.network(player, networkId);
long souls = session.amount(BdMagicApi.SOUL_KEY);
```

- `RsiMagicApi.Session` 提供 `amount`、`available`、`insert`、`extract` 和 `reserve`。
- `BdMagicApi` 内置 `MANA_KEY`、`SOURCE_KEY`、`SOUL_KEY`，并提供对应 `Session` 方法。
- `BdMagicApi.registerAlias(alias, canonical)` 会把已登记的别名 Key 改写为规范 Key。
- 别名 Key 本身仍须由所属模组注册到 BD 的 `StackKeyRegistry`；RSI 不会替别人创建 Key 类型。

配方处理器通过 `InstantRecipePolicy` 描述即时执行方式：`NONE` 保持逐次执行，`LEDGER_TRANSACTION` 允许确定性的直接账本结算，`SIMULATED_ASSEMBLY` 开启有上限的真实装配批。复用输入必须继续声明为 `CATALYST` 材料角色，不能在另一套倍增逻辑里复制。

## 配置

- `config/rs_integration/common.toml`：功能开关和可选模组集成。
- `config/rs_integration/storage.toml`：混合终端、万界归墟盘容量、磁盘操纵器分片搬运、存储保存校验、终端拆包上限和流体容器检查。
- `saves/<世界>/serverconfig/rs_integration/server.toml`：服务端合成、机器和魔法资源行为。
- `config/rs_integration/client.toml`：界面、JEI 和 Tetra 显示设置。

可配置规划深度/节点预算、优先配方与材料变体、保留物品、概率材料尝试上限、输入预载、DAG/机器并发和并行委托策略。服务端 `magicResources.pinnedBackend` 只会在没有生效空间管辖时决定后端，不会合并 RS 与 BD。

`integrations.enableGoetySoulReverseRefill` 默认关闭。开启后，灵魂方舟发生灵魂消耗时会从绑定的 RS 网络按剩余容量补满；同一玩家每 20 tick 最多尝试一次。

`integrations.goetyBrazierVirtualCandleCount` 默认 `64`。RSI 递归合成亡灵火炉且没有实体灵魂烛台时，会使用该数量的内存烛台推进进度；每个烛台每 tick 消耗 1 点灵魂，配方总成本不变，手动点火仍遵循 Goety 原版结构要求。

`autoCrafting.freeWaterMachines` 控制免费补水。列表中的机器免费补水，未列出的机器消耗所选 RS 网络中的流体；设为 `[]` 可关闭全部免费补水，配置注释中列有完整默认机器 ID。

升级时通常会保留已有设置，新增列表项可能需要手动补充。

`storage.diskManipulator.enabled` 默认开启。开启后所有磁盘操纵器共享 `budgetMicros` 指定的单 tick 时间预算，并按 `maxOperationsPerTick` 和 `maxItemsPerOperation` 分片搬运；关闭后完全恢复 RS 原版逻辑。网络或磁盘暂时无法继续接收时会按 `noProgressCooldownTicks` 冷却，避免空转。

网格搜索缓存保存在游戏目录 `rs_integration/cache/grid_search`，不会写入配置文件；旧版 `config/rs_integration/grid_search_cache` 文件会在读取时自动迁移。

## 诊断

- `/rsi cancel`：取消当前合成。
- `/rsi_fluid_refund`：领取因背包已满或玩家离线而保留的流体退款物品。
- `/rsi_item_refund`：领取因玩家离线而保存的普通合成退款物品。
- `/rsi_debug`：向权限等级 2 的管理员提供合成链、绑定、处理器、资源预留和性能诊断。
- `/rsi_unified_disk list [page]` 与 `recover <uuid>`：列出检查点并按 UUID 找回万界归墟盘物理凭据（权限等级 2）。
- `/rsi_storage_test insert <数量> <物品 ID>`：按 RS 原生 `int` 分片向玩家当前解析到的 RS 网络插入指定 `long` 数量，供管理员测试大数量库存（权限等级 2）。
- `/rsi_storage_test snapshot|simulate_insert|roundtrip <amount>`：供管理员对 RS 存储执行显式冒烟测试。
- `diagnosticVerboseLogging`：启用详细解析和调度日志。

## 构建

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat build --no-daemon
```

发布 JAR 位于 `build/libs/`，并由 `verifyReleaseJar` 任务检查。

## 许可

All rights reserved.
