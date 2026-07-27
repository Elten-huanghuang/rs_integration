<p align="center">
  <img src="src/main/resources/rs_integration_logo.png" width="96" alt="RS Integration Logo">
</p>

<h1 align="center">RS Integration</h1>

<p align="center">
  <a href="README.md">English</a> | <strong>简体中文</strong>
</p>

RS Integration 将 JEI 配方、Refined Storage 库存和模组机器连接成一套远程合成流程，适用于 Minecraft 1.20.1。它可以递归解析依赖、并发调度互不依赖的图节点、操作已绑定机器，并把产物送回 RS 网络或玩家背包。

## 运行要求

| 依赖 | 要求 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47+ |
| Refined Storage | 1.12+，必需 |
| JEI | 客户端必需，用于配方操作和合成计划预览 |
| 其他集成模组 | 可选；只有检测到目标模组时才加载对应模块 |

## 主要功能

### 递归远程合成

- 在 JEI 中点击 RSI 操作按钮，通过已绑定机器预览并启动配方。
- 递归解析工作台、普通机器、虚拟交换和多方块机器中间步骤。
- 提供步骤列表和可缩放依赖图，支持替代配方、重复次数和缺失材料提示。
- 对互不依赖的 DAG 节点并发执行，同时保证串行/独占机器的安全。
- 跟踪材料来源，通过事务式预留、提交、回滚、退款和产物交付避免吞物或复制。
- 没有 RS 网络时，普通工作台配方仍可使用玩家背包进行递归合成。
- 机器的单个物理输入槽会锁定一种具体材料；工作台不同格子仍可混用标签兼容材料，例如不同木板。
- 按 `P` 查看运行中的合成；也可通过进度界面、聊天操作或 `/rsi cancel` 取消。

### 机器绑定与远程访问

- 手持网络连接器潜行右键机器进行绑定或解绑。
- 绑定记录包含维度和坐标；方块被破坏后会清理失效记录。
- 从 RS 界面远程打开兼容机器 GUI，关闭后可返回终端。
- 机器管理中心支持搜索、状态查看、收取产物、打开 GUI 和数字键选择。
- 多台同类机器可负载均衡；符合机器契约时，重复操作可组成 operation group 分发。

### JEI 与 RS 界面工具

- 在 JEI 物品列表拖框批量收藏或隐藏。
- Alt 点击物品按模组筛选；Alt 中键清空搜索。
- `Ctrl+T` 将当前 JEI 配方传送到 RS 合成终端。
- 按住 `Ctrl` 在 RS 格子上左键拖动，每个经过的格子提取一个物品。
- 可拖动侧边栏可在其他界面显示 RS 库存和机器标签，并支持拼音搜索。
- 在容器界面按 `F` 存入物品，按 `G` 切换 RS / 精妙背包目标。

### 共振磁盘

共振磁盘是一种特殊 RS 存储磁盘，盘内物品可以像玩家随身携带一样参与效果与资源查找。

- 支持属性、`inventoryTick`、事件驱动被动效果和兼容资源查询。
- 对配置为可变的物品保存 NBT/耐久变化，而不是只模拟一次副本。
- 默认支持 Apotheosis 药水护符、Muyimeng 混合药水护符、Reliquary 纵火者法杖、Enigmatic Addons 人造花、Forbidden & Arcanus 幽灵之眼护符。
- 支持勇者传说/月石九剑书、Wizard Terra Curios 的 `BuffItem`、Terra Equipment 成组无限药水。
- 与 RSI 自动进食、磁铁/拾取以及兼容配方和资源消耗逻辑联动。

### 精妙背包与 FTB 任务

- 可将磁铁、拾取、喂食、补货、重存、存入、压缩等兼容升级重定向到 RS 网络。
- 即使没有 RS 无线终端，也可以从玩家背包执行普通配方递归合成。
- 跟踪外部物品移动，并通过 FTB Quests 原生路径提交符合条件的物品任务。

## 快速开始

1. 建造并供能一个 Refined Storage 网络。
2. 获取 RSI 网络连接器。
3. 潜行右键受支持机器完成绑定。
4. 在 JEI 打开配方并点击 RSI 合成操作。
5. 检查合成计划，选择替代配方或重复次数，然后启动。
6. 按 `P` 查看进度；完成后产物进入 RS，没有网络时进入玩家背包。

## 默认按键

所有按键都可以在 Minecraft 控制设置中修改；配置中的数字键码使用 GLFW 值。

| 按键 | 场景 | 功能 |
|---|---|---|
| `F` | 容器界面 | 存入容器内容 |
| `G` | 任意/容器界面 | 切换 RS / 精妙背包传输目标 |
| `Y` | 任意界面 | 显示/隐藏 RS 侧边栏 |
| `H` | 任意界面 | 显示/隐藏机器管理中心 |
| `P` | 任意界面 | 打开/关闭当前合成进度 |
| `Alt` + 左键 | JEI 物品 | 按模组筛选 |
| `Alt` + 中键 | JEI 物品列表 | 清空搜索 |
| `Ctrl+T` | JEI 配方 | 传送到 RS 合成终端 |
| `Ctrl` + 左键拖动 | RS 格子 | 每格提取一个物品 |
| 左键拖框后按 `A` / `H` / `Esc` | JEI 列表 | 收藏 / 隐藏 / 清除框选 |
| `U` / `R` | 侧边栏悬停物品 | 在 JEI 查看用途 / 配方 |

## 合成集成

下表来自当前代码注册的专用配方模块。仅远程 GUI 的方块还可以通过 `customGuiMachineMods` 扩展。

| 模组 | 支持的配方或机器 |
|---|---|
| 原版 / 砖制熔炉 | 熔炉、高炉、烟熏炉、营火、切石机、锻造台、铁砧、附魔台 |
| Iron Furnaces | 各等级熔炉的熔炼、烧炼和烟熏模式 |
| Botania | 魔力池/催化器、花瓣药剂台、符文祭坛、植物酿造台、精灵交易、泰拉凝聚板、白雏菊 |
| Ars Nouveau | 灌注室、附魔装置 |
| Goety | 死灵火盆、黑暗祭坛、诅咒笼、灵魂烛台及兼容仪式 |
| Malum | 灵魂祭坛、灵魂熔炉、符文工作台、灵魂灌注及相关配方 |
| Eidolon | 工作台、坩埚、火盆 |
| Forbidden & Arcanus | 赫菲斯托斯锻炉、Clibano、锻造/词条应用流程 |
| Wizards Reborn | 秘蕴结晶器、奥术迭代器、奥术工作台、水晶仪式 |
| Touhou Little Maid | 女仆祭坛 |
| Embers Rekindled | 炼金台，支持推断和确定性布局 |
| Aetherworks | 以太锻砧、工具站 |
| The Aether | 冷冻器、孵化器、祭坛 |
| Crock Pot | 锅釜、便携锅釜 |
| Farmer's Delight | 烹饪锅、煎锅 |
| Farmer's Respite | 茶壶 |
| Youkai's Homecoming | 摩卡壶、发酵罐、蒸笼、水壶、各类烹饪锅、料理台 |
| Immortaler's Delight | 附魔冷却器 |
| Apotheosis | 制箭、宝石切割、附魔图书馆工具；重铸台远程 GUI |
| TACZ 与兼容枪包 | 枪械工作台配方，包括带 NBT 的枪械和弹药 |
| Avaritia | 终极工作台、中子态素压缩机、终极锻造台 |
| SlashBlade | 带 NBT 判定的工作台配方 |
| Confluence | 工坊 |
| Distant Worlds | Lithum 祭坛及相关交互/HUD |
| Lychee | 虚拟物品浸泡配方 |
| Farming for Blockheads | 市场虚拟交换 |
| Crabber's Delight | 捕蟹笼处理 |

自定义 GUI 默认包含 Crabber's Delight、Metal Barrels、PGP、EMX Arms、Apotheosis 和 Ancient Reforging。没有自动配方 delegate 时，它们仍可绑定并远程打开 GUI。

## 配置

配置文件本身包含注释和取值范围：

- `config/rs_integration/common.toml`：功能开关、可选模组集成、被动效果、自动进食、侧边栏、GUI 机器列表。
- `saves/<世界>/serverconfig/rs_integration/server.toml`：配方选择、超时、递归深度/步数、DAG 并发、保护库存和机器策略。
- `config/rs_integration/client.toml`：侧边栏位置、尺寸、隐藏状态和 HUD 偏好。

重要服务端选项包括首选/黑名单配方、重复次数、解析预算、并发图节点/操作数量、按模组并行策略和全局合成超时。配置文件生成后不会因新默认值自动覆盖；新增列表项需要手动合并，或删除旧配置后重新生成。

## 诊断

- `/rsi cancel`：取消玩家当前合成链。
- `/rsi_debug`：向权限等级 2 的管理员提供合成链、账本、配方索引、处理器、绑定、解析追踪、审计、Embers 缓存/锁和性能诊断。
- `diagnosticVerboseLogging`：排查问题时启用详细解析与调度日志。

## 构建

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat build --no-daemon
```

发布 JAR 输出到 `build/libs/`，并由构建中的 `verifyReleaseJar` 任务验证。

## 许可

All rights reserved.
