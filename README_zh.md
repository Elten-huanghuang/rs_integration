<p align="center">
  <img src="src/main/resources/rs_integration_logo.png" width="96" alt="RS Integration Logo">
</p>

<h1 align="center">RS Integration</h1>

<p align="center">
  <a href="README.md">English</a> | <strong>简体中文</strong>
</p>
<p align="center"><strong>Minecraft 1.20.1 · Forge 47+ </strong></p>
<p align="center">
  <a href="https://modrinth.com/mod/rs-integration"><img src="https://img.shields.io/modrinth/dt/vtvveeqA?style=flat&amp;logo=modrinth&amp;label=Modrinth" alt="Modrinth 下载量"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/rs-integration"><img src="https://img.shields.io/curseforge/dt/1630184?style=flat&amp;logo=curseforge&amp;label=CurseForge" alt="CurseForge 下载量"></a>
  <a href="https://www.mcmod.cn/class/29199.html"><img src="https://img.shields.io/badge/MC%E7%99%BE%E7%A7%91-29199-2f855a?style=flat" alt="MC百科"></a>
</p>

RS Integration（RSI）把 **Refined Storage**、**Beyond Dimensions** 与模组机器连接起来：选择配方、预览依赖，再由 RSI 供料并操作支持的机器。

**当前版本：1.6.0.1 · Minecraft 1.20.1 · Forge 47+**

1.6.0.1 新增材料来源多选和主题快捷切换，并继续优化大型合成计划。[查看更新公告](docs/announcements/RELEASE_NOTES_1.6.0.1.md)。

## 运行要求

| 依赖 | 要求 |
|---|---|
| Minecraft / Forge | 1.20.1 / 47+ |
| JEI | 客户端必需，用于配方操作和预览 |
| Refined Storage | 1.12+，可选 |
| Beyond Dimensions | 可选，可作为另一种存储后端 |
| EMI 和其他集成模组 | 可选；EMI 支持仍需安装 JEI |

客户端与服务端安装相同版本的 RSI。不安装两种存储模组时，普通工作台配方也可仅用玩家物品栏递归合成。可选集成只在目标模组存在时加载。

## 主要功能

- **递归合成**：先看配方树、缺料、替代配方和机器条件，再确认执行；物品栏、当前 RS/BD 网络和精妙背包可分别选作材料来源。
- **机器远程访问**：绑定机器、远程打开界面，多台兼容机器分担重复任务；遵守支持的领地权限，不强制加载区块。
- **合成管理**：按 `P` 管理计划、运行任务和绑定机器，查看步骤与材料状态，也可随时取消。
- **存储扩展**：混合终端统一浏览物品与流体；万界归墟盘在一张 RS 磁盘内存储物品、流体和魔法资源。
- **共振联动**：受支持的盘内物品可提供被动效果与资源，共振魔力池和魔源罐对接 Botania、Ars Nouveau 原生传输。
- **整合包工具**：一键转移容器、点餐、自动进食、交易与重铸补货，以及 FTB Quests、精妙背包联动。
- **界面与配置**：计划和机器列表搜索、拼音检索、多套主题预设，以及游戏内配置编辑器。

RS 与 BD 始终是独立网络，库存不会自动合并。功能边界与详细用法见[使用指南](docs/guide/README_zh.md)。

## 快速开始

1. 安装 RSI 和 JEI，按需连接 RS 或 BD 网络。
2. 需要机器加工时，手持兼容的网络连接器或终端，用 `Alt` + 右键绑定机器，或在附近按 `;` 扫描。
3. 在 JEI/EMI 打开配方，点击 RSI 合成按钮。
4. 检查计划，选择材料来源、配方、数量和产物去向，然后确认。
5. 按 `P` 查看进度与管理机器；在界面中取消，或输入 `/rsi cancel`。

## 按键

以下为默认绑定，可在 Minecraft 控制设置中修改。

| 按键 | 功能 |
|---|---|
| `P` | 打开 RSI 控制中心：计划、合成进度与绑定机器 |
| `Alt` + 右键 / `;` | 绑定目标机器 / 扫描附近机器 |
| `F` | 转移容器内容；在 RS/BD 终端中填入 JEI/EMI 物品搜索 |
| `G` | 在 RS 和精妙背包之间切换容器转移目标 |
| `Y` | 显示/隐藏 RS 侧边栏（配置默认关闭） |
| `Space` | 补充支持的交易、青金石和铁砧/重铸材料 |

[完整按键说明](docs/guide/README_zh.md#按键)

## 模组兼容

已集成 Botania、Ars Nouveau、Goety、Malum、Embers/Aetherworks、农夫乐事及附属、Apotheosis、Iron's Spells 'n Spellbooks 等模组。兼容范围以具体配方和机器为准，原生热源、结构、燃料和部分仪式手动步骤仍可能需要保留。

[完整兼容表与机器使用条件](docs/guide/README_zh.md#模组兼容)

## 开发者 API

第三方配方、机器、执行器、共振物品栏和 Consumer 接入及许可边界见[公共 API 指南](docs/api/README.md)。

## 配置

可从 JEI 配置按钮打开游戏内编辑器，也可编辑客户端、通用、存储和世界服务端配置。参见[详细配置](docs/guide/README_zh.md#配置)、[计划界面设置](docs/config/plan-ui.md)与[规划配置示例](docs/config/planning.example.toml)。

## 诊断

取消任务、物品/流体退款、管理员磁盘找回和排错入口见[命令与恢复工具](docs/guide/README_zh.md#诊断)。

## 构建

需要 JDK 17：

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat jarJar verifyReleaseJar --no-daemon
```

经校验的发布 JAR 位于 `build/libs/`。

## 许可

RSI 是专有软件。[LICENSE](LICENSE) 允许将官方未修改版本用于整合包、制作可变现的游戏视频或直播，并按互操作条款使用文档列出的公共 API。

代码贡献须同意 [CONTRIBUTING.md](CONTRIBUTING.md) 中的协议。第三方边界见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
