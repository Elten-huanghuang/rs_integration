# BeyondDimensions 独立存储后端评估报告

> 状态日期：2026-08-25
> RSI 工作树：`D:\sd\rs-integration`
> BD 工作树：`D:\sd\BeyondDimensions`，`1.20.1` 分支
> 目标：RS 与 BeyondDimensions（BD）作为二选一存储后端，使用同一套递归合成和机器委托逻辑。

## 1. 最终结论

RSI 已经具备后端中立的存储契约和 BD 物品存储适配器。递归合成主链、异步任务、材料账本、机器委托、失败退款和产物回存已经大范围改为使用 `CraftStorageEndpoint`；选定 BD 后，相关入口不会再重新解析或覆盖成 RS。

但当前还不能宣称“所有 RSI 功能已经达到 RS/BD 完全等价”。原因是：

- BD-only 的客户端、专服启动和真实存取尚未完成最终回归。
- 侧栏、机器管理中心的少数状态同步细节、部分 RS Grid/JEI hook、精妙背包 Deposit Upgrade 仍是 RS 专属或部分兼容功能；递归计划和 JEI 机器 GUI 的 BD packet 分流已补齐，仍需实机验收。
- 部分旧 flat/legacy packet 和特殊辅助服务仍保留 `INetwork` 兼容重载，需要按测试矩阵验证。
- 本轮已完成 P1 的源码清理：除 Goety/TLM 外，Forbidden Arcanus 祭坛清理、Crabber's Delight 多产物回收、Youkai Homecoming 炊锅退款、Wizard's Reborn 副产物/失败回滚也已改为检查选定 `CraftStorageEndpoint`；Embers 规划信息读取接入选定端点，Forbidden Arcanus 在 BD 模式会清空旧 RS 兼容引用。Eidolon/TLM 的配方类型提示使用统一翻译键，FTB 服务端日志不再预渲染翻译；完整 RS+BD 实机矩阵仍未替代源码审计。

准确的产品状态是：**BD 后端和核心递归执行链已接入，RS/BD 双后端选择机制已建立；RS 侧栏明确暂不接入 BD，递归计划和 JEI 机器 GUI 已增加 BD packet 分流，机器中心还有状态同步和持久化细节待验收，完整独立运行仍处于验收阶段。**

## 2. 目标边界

### 2.1 本次目标

- RS-only：保持现有行为。
- BD-only：可以发现玩家有权限访问的 BD 网络，读取物品快照，提取材料，将产物和退款放回 BD。
- RS+BD：每次操作绑定一个明确的 `backendId + networkId`，不能自动合并两个网络，也不能因旧兼容字段把 BD 切回 RS。
- 无后端：不加载可选后端类；仅允许与存储无关的功能，递归网络操作应给出明确提示。

### 2.2 不在首期等价范围

- BD 中复刻 RS Grid 的搜索、过滤、滑动提取和 RS 专用渲染。
- RS 自动合成任务 Mixin、共鸣磁盘/被动效果的 BD 等价物。
- RS 与 BD 的联合库存或一次操作同时消费两个后端。
- BD 流体、能量和化学品作为递归物料的通用模型。

## 3. 当前架构

### 3.1 存储契约

业务层使用以下后端中立类型：

- `StorageBackend`：注册、可用性、默认解析和网络发现。
- `StorageReference`：持久化的 `backendId + networkId`。
- `StorageSession`：一次授权后的网络会话。
- `StorageSnapshot`：服务端线程生成的不可变物品快照。
- `CraftStorageEndpoint`：递归合成、账本和机器委托的统一操作入口。
- `ExtractionLedger`：预留、提交、结算、退款和部分失败恢复。

递归主路径以 `CraftStorageEndpoint` 为准，BD endpoint 不伪造 `INetwork`，因此旧 RS 参数不会覆盖已选择的 BD endpoint。当前核心层仍保留部分 `INetwork` 字段、参数和 RS fallback（例如 legacy delegate 与 `ExtractionLedger`），这些只能在 endpoint 为空时使用，尚未完成彻底移除。

### 3.2 BD 适配器

BD 适配器位于 `src/main/java/com/huanghuang/rsintegration/storage/bd`，使用 BD 1.20.1 的以下能力：

- `DimensionsNet`：主网络、玩家可访问网络、网络 ID。
- `UnifiedStorage`：快照、插入、提取和模拟操作。
- `ItemStackKey`：物品和 NBT 身份。
- `KeyAmount`：资源身份和数量。

适配器负责成员权限、网络失效、返回值校验、`long` 到 Minecraft `int` 的边界保护以及异常失败关闭。BD 私有 API 未暴露给递归业务模块。

### 3.3 网络选择

选择优先级如下：

1. 请求中明确传递的 `StorageReference`。
2. 当前终端/绑定物品对应的网络。
3. 后端注册表提供的默认网络。
4. BD 可发现且玩家有权限访问的网络。

RS+BD 同装时必须由 UI 或绑定上下文明确选择；不能把注册表第一个结果当作两个网络的合并视图。

## 4. 功能迁移矩阵

| 功能 | RS-only | BD-only | RS+BD | 当前判断 |
|---|---:|---:|---:|---|
| 物品快照、精确提取、Ingredient 提取、插入余量 | 已有 | 已接入 | 已接入 | 需真实网络回归 |
| 原版递归合成 | 已有 | 主链已接入 | 选定网络执行 | P0 |
| 异步递归链、机器节点、账本、退款 | 已有 | endpoint 主链已接入 | endpoint 隔离已接入 | 需机器回归 |
| Botania、Avaritia、Malum、Eidolon、Goety、Embers、Wizards 等 delegate | 已有 | 大部分入口已迁移 | 选定网络执行 | 仍需逐类验收 |
| FTB Quests 规划、Escrow、提交、退款 | 已有 | endpoint 路径已接入 | 选定网络执行 | 需批量/拒绝/变化测试 |
| 机器绑定、BD 终端 Alt+右键 | RS 已有 | BD network ID 已接入 | 两种绑定互不覆盖 | 需按键抬起/按下回归 |
| 次元磁铁、Pickup、Refill、Feeding、Restock | RS 已有 | 通用存取路径已接入 | 后端引用已接入 | 仍需完整行为回归 |
| 配方树自定义加号、网络选择 | 已有 | 通用计划入口已接入 | 可选择具体网络 | RS Grid transfer hook 仍专属 |
| 机器管理中心/机器快捷标签 | 已有 | 已接入 | 已接入 | BD 终端按原生 `LeftTabButton`/`RightTabButton` 规则显示机器中心和收藏标签；标签使用 BD 自己的 `left_tab/right_tab`、`slot_button`、蓝色 `slot_button_hovered` 及网络终端图标资源，收藏来源与 RS 同步收藏统一；收藏 dock 会避开滚动条并在窄窗口内自动夹紧 |
| 机器中心快速插入/取出/解绑 | RS 侧栏包 | BD packet 已接入 | RS/BD 各走对应路径 | `MachineTabHandler` 已按 BD 绑定分流到 `BeyondDimensionsMachineCollectPacket`、`BeyondDimensionsMachineInsertPacket`、`BeyondDimensionsUnbindMachinePacket`；仍需实机验证权限、槽位、退款和状态同步 |
| 递归计划/JEI 的打开机器 GUI | RS 侧栏包 | BD packet 分流已接入 | RS/BD 各走对应路径 | 计划界面、JEI layout 和 anvil 分支按玩家绑定物品选择 RS 或 BD packet；仍需实机验证跨终端和失效绑定 |
| 侧栏、容器 F/G 转移、世界选取 | 已有 | 容器转移部分可用 | 部分可用 | 侧栏/世界选取仍 RS-only；BD 容器 F/G 转移走通用 endpoint |
| 一键吃 | 已有 | 已接入 | RS+BD | 服务端已改用 `CraftStorageEndpoint`；RS Grid 和 BD 终端均显示入口，双后端时按当前终端上下文选网 |
| 精妙背包 Deposit Upgrade 对控制器自动存入 | RS-only | 无 BD 等价实现 | RS-only | 旧 Mixin 直接依赖 RS `GridBlockEntity`、`GridNetworkNode`、`INetwork`；不能与新 backend-neutral Pickup/Refill/Feeding 路径混同 |
| 共鸣磁盘、共鸣背包、被动效果 | 已有 | 无 BD 等价设计 | RS 专属 | 明确 RS-only |

“已接入”表示源码路径已经使用 endpoint，不等于已经通过 BD-only 游戏验收。

## 5. 已完成的关键修复

- `AsyncCraftChain`、`GenericCraftPacket`、`GenericBatchDelegate` 和主要 machine delegate 传递并保留同一个 endpoint。
- 选定 BD 后，旧 `resolveNetworkForCraft` 只在 endpoint 为空的兼容路径执行。
- `ExtractionLedger` 的预留、提交、settle、退款和 rollback 使用同一后端。
- Avaritia 工作台/压缩机、Botania Pure Daisy、Vanilla Brewing/Furnace、Malum Runic Workbench、Forbidden Arcanus 等遗留入口已补齐 endpoint 继承。
- Vanilla 烹饪包装委托已在子委托准备前同步选定 endpoint，燃料提取和退款不会因包装层丢失 BD/RS 后端上下文。
- FTB Quests 的直接提交、部分提交和退款已接入 endpoint escrow。
- BD 终端绑定机器使用 network ID；RS 控制器绑定和 BD 终端绑定不会互相覆盖。
- Apotheosis 刷怪笼蹲下右键入口改用通用存储终端钩子，BD-only 环境不会再加载 RS `NetworkItem`。
- 可选后端注册和 RS 专属 Mixin 已增加存在性判断；公共绑定事件类已移除对 RS 类型的直接暴露。
- 中英文翻译键集合保持一致；新增文案使用“存储网络”的通用语义，RS 专属功能保留 RS 语义。
- BD 统一存储中的 `FluidStackKey` 已接入容器材料转换：网络内存在空桶和至少 1000mB 水/熔岩时，递归合成可在提交阶段消耗它们生成水桶/熔岩桶；模拟预留、正式提取和失败回滚均在同一 BD endpoint 内完成。RS 不暴露该能力，原有物品路径不变。
- Sophisticated Backpacks 的 Pickup、Refill、Feeding、Magnet、Restock 公共存取路径已补充本地处理异常后的归还保护；后端插入返回余量时仍回到同一 `StorageReference`，Deposit Upgrade 继续明确保持 RS-only。

## 6. 仍需处理或明确标记的范围

### 6.1 必须完成的发布阻塞项

1. 使用不含 RS JAR 的 BD-only 客户端和专服完成启动、进世界、登录、`/reload`、退出和重启。
2. 验证 BD 网络真实快照、模拟提取、正式提取、插入和容量不足余量。
3. 对代表性机器验证真实等待时间、催化剂/燃料/容器语义、机器内残留和失败退款。
4. 验证 RS+BD 下显式选择后连续下单不会串库，且第二次打开配方树的库存数量已刷新。

### 6.2 仍是 RS-only 或部分兼容

- RS 侧栏库存同步与侧栏交互：`RSSidePanelClient`、`RSSidePanelNetworkHandler`、`SyncHandler` 及其世界选取/侧栏转移逻辑仍是 RS-only，且已明确暂不接入 BD，不是当前 BD 迁移阻塞项。
- RS Grid 专属 JEI hook、Grid 搜索历史/过滤/渲染、Grid transfer 以及收藏快捷栏：`RSJeiOptionalHooks`、`RSJeiPlugin` 的 Grid 分支和 `MachineFavoritesClient` 仍要求 RS。
- 机器中心的快速插入、取出、解绑已按绑定类型分流：RS 绑定继续发送 `MachineCollectPacket`、`MachineInsertPacket`、`UnbindMachinePacket`，BD 绑定发送独立的 `BeyondDimensionsMachineCollectPacket`、`BeyondDimensionsMachineInsertPacket`、`BeyondDimensionsUnbindMachinePacket`。BD packet 已由 `BeyondDimensionsMachineNetworkHandler.register()` 注册；侧栏 UI 本身仍是 RS-only，但这三个机器中心操作不再属于“未脱离 RS 注册”的代码缺口，仍需实机验证。
- 递归计划界面的“打开机器”和 JEI layout 的机器 GUI 按钮已按当前绑定物品分流到 RS 或 BD packet；RS-only 仍使用 `OpenBoundMachineGuiPacket`，BD-only 使用 `BeyondDimensionsOpenBoundMachineGuiPacket`。当前剩余工作是实机回归，不是 packet 注册缺口。
- 精妙背包 Deposit Upgrade 的自动存入仍直接匹配 RS `GridBlockEntity` 并操作 RS `INetwork`；这是 RS-only 功能，当前没有 BD 等价实现。
- `ResonanceDisk*`、`RSInventoryBridge`、`PassiveEffectEngine`：没有 BD 等价实现，继续明确标记为 RS-only。
- `GuiNavStack` 中返回 RS Grid 的 `ReturnToRSPacket` 路径、RS 专属 GUI 和少数调试入口仍是 RS-only；侧栏暂不接入 BD，BD-only 入口必须跳过这些路径。
- `RSIntegrationNetwork`、旧 flat/legacy packet 以及直接使用 `INetwork` 的兼容桥属于 RS legacy 白名单，不得从 BD-only 初始化或公共业务路径触达。
- 各 mod delegate 中保留的 `INetwork` 字段和 `resolveNetworkFromPlayer` 调用，只有在 endpoint 为空时才是 RS fallback；它们不是自动等价证明。所有从旧 packet、辅助检查器或特殊 mod 入口进入的调用，都必须确认 endpoint 已传递，否则仍可能退回 RS-only 行为。
- 一键吃、容器 F/G 转移、核心递归合成和机器委托不再归入 RS-only；它们的服务端执行路径已使用 `CraftStorageEndpoint`，但 BD-only 仍需终端上下文和实机回归。

这些功能不能通过把提示文字改成“存储网络”来伪装成 BD 支持；应继续保持 RS-only、隐藏入口，或单独实现后端中立入口。

### 6.4 当前客户端入口结论

- **一键吃**：已接入 BD。服务端不再直接依赖 RS Grid/cache，而是解析后端中立 endpoint；BD 终端界面和 RS Grid 均可显示按钮。BD-only 下需打开 BD 终端或持有/绑定 BD 终端以提供网络上下文。
- **机器管理中心**：BD 终端左侧始终显示独立机器管理中心按钮，不再占用 `P` 快捷键；按钮严格复用终端原生 `IconButton` 的 16×16 `slot_button.png`/`slot_button_hovered.png`，放在主网络切换器下方的 `leftPos - 18` 槽位（`topPos + 6 + 18*8`），不再叠加 23×26 `left_tab` 背景。机器收藏使用 `right_tab.png` 外框，放在完整 194px 终端表面右侧并保留 4px 间距（`leftPos + 198`），纵向使用 `topPos + 6 + 30*n` 和 `+3,+4` 图标偏移，不再覆盖滚动条；窄窗口会将 dock 限制在可视区域内。点击收藏项直接走 `BeyondDimensionsOpenBoundMachineGuiPacket`，不会进入 RS `GuiNavStack`。Hub 内的打开机器、收藏、快速插入/取出、解绑、状态/输出、搜索、滚动、拖动和关闭均复用同一套机器中心逻辑；RS Grid 收藏快捷栏、JEI extra area 和侧栏同步仍是 RS-only，BD 终端关闭机器 GUI 后返回终端和状态同步仍需实机回归。
- **机器管理中心快速操作状态**：远程打开、快速插入、快速取出和解绑均已有 BD 独立 packet；客户端按 BD 绑定分流，服务端在 BD handler 中执行。它们仍未经过完整 BD-only 实机验收，不能仅凭 packet 已注册推导行为完全稳定。
- **递归计划打开机器**：计划界面已根据绑定物品分流 RS/BD 打开机器 packet；BD-only 是否能正确返回对应终端/机器 GUI 仍需实机验证。
- **核心递归合成/机器委托**：与上述客户端入口分离，仍可使用 BD endpoint；关闭侧栏不会关闭递归合成主链。

### 6.3 兼容性规则

- 业务代码不得把 BD 对象强转或伪造成 `INetwork`。
- 新异步任务和机器绑定必须持久化 `StorageReference`，不能只保存当前 session 或玩家位置。
- 失败退款必须回到原 endpoint；插入余量必须进入玩家、机器安全槽或世界掉落，不能静默丢弃。
- 旧 `INetwork` 重载可以保留，但只能作为 RS legacy fallback，不能在 endpoint 已存在时重新解析后覆盖选择。
- 网络权限、网络删除、玩家失去成员资格和后端卸载必须进入可恢复失败状态。

## 7. 验收矩阵

### 7.1 安装组合

| 组合 | 必测内容 |
|---|---|
| RS-only | 原有递归、机器 delegate、退款、侧栏和升级无回归 |
| BD-only | 启动、网络发现、终端绑定、配方树、递归、机器投料/回收、退款 |
| RS+BD | 两个网络同时存在时显式选择、连续下单、切换后端、互不串库 |
| 无后端 | 不崩溃；存储操作给出清晰错误；非存储功能仍可加载 |

### 7.2 代表性业务

- JEI 自定义加号、原版配方和至少一条多步机器链。
- Pure Daisy、Avaritia、Ars Imbuement、Goety、Crock Pot、Forbidden、Iron Furnaces、TLM、Aetherworks、Wizards、Malum、Eidolon、Youkai。
- FTB Quests 手动提交、批量提交、材料变化、拒绝和退款。
- 次元磁铁、Pickup、Refill、Feeding、Restock 的提取、插入和容量不足。
- 终端 Alt+右键按下/抬起去重、范围绑定、解绑和失效网络。
- 玩家背包材料、网络材料、产物回存、玩家库存满、机器区块卸载、网络断开和中途离线。
- BD 液体容器材料：空桶 + 水 -> 水桶、空桶 + 熔岩 -> 熔岩桶；验证 1/多桶、液体不足、空桶不足、模拟预留和中途失败退款。

### 7.3 静态门禁

- 无 RS classpath 下执行 common 类加载检查。
- `core`、`crafting`、通用 machine 包不得新增 RS/BD 原生类型签名。
- BD 包不得导入 RS 类型，RS 专属包不得被 BD-only 初始化路径触达。
- 扫描 `INetwork`、`RSIntegrationNetwork`、RS cache 和硬编码“RS 网络”前置条件；每个剩余调用必须属于 RS-only 白名单或 legacy fallback。
- 额外扫描机器中心、配方计划、JEI machine-GUI 按钮和精妙背包 Deposit Upgrade；侧栏 UI 本身不在本轮迁移范围，但机器中心复用的 packet 注册仍必须单独验证，不能只因为核心递归使用 endpoint 就标记为 BD 等价。
- 检查中英文键集合、占位符和用户可见后端名称。

## 8. 构建与交付

最近一次验证：

- `./gradlew.bat compileJava --no-daemon`：成功。
- `./gradlew.bat jar --no-daemon`：成功。
- `./gradlew.bat test --no-daemon`：成功（1124 项）。
- `CraftStorageEndpointTest`、`LanguageParityTest`、`ServerSideTranslationBytecodeTest`：成功。
- `git diff --check`：无实际格式错误；仅有工作树换行提示。
- JAR：`D:\sd\rs-integration\build\libs\rs_integration-1.3.5.jar`
- SHA256：`3CF85240E78E225B51144CDCDFC9EA0E8264CBB04192F823D88E62BAD41AD1C8`

## 9. 发布建议

当前构建适合作为 **RS+BD endpoint 迁移测试包**，不应标记为“BD-only 完整稳定版”。下一停点应是用该 JAR 执行第 7 节的 BD-only 和 RS+BD 实机回归；只有启动、网络存取、机器链、退款和关键用户功能全部通过后，才能把产品结论升级为“RS 与 BD 可二选一完整使用”。

## 10. 交接用完整功能清单

本节是后续开发的工作边界。清单中的“RS-only”表示功能本身依赖 RS 设计；“缺口”表示功能目标应当支持 BD，但当前仍有 RS 注册、RS 包或 RS GUI 路径；“legacy fallback”表示主 endpoint 路径可以支持 BD，但旧入口仍保留 RS 类型，必须确认调用时不会丢失 endpoint。

### 10.1 RS-only 功能

| 功能 | 主要源码入口 | 依赖 | BD 状态 | 处理原则 |
|---|---|---|---|---|
| RS 侧栏库存面板 | `sidepanel/RSSidePanelClient`、`RSSidePanelNetworkHandler`、`SyncHandler`、`SidePanelRenderer` | RS Grid、`INetwork`、RS storage cache | 明确暂不接入 BD | 保持 RS-only；BD-only 隐藏，不从核心业务调用 |
| 侧栏世界拾取/拖拽分配 | `WorldPickClient`、`WorldPickJeiClient`、`SidePanelMouseHandler`、`RSSidePanelClickPacket` | RS 侧栏 channel、RS 权限和缓存 | 明确暂不接入 BD | 保持 RS-only；不作为当前 BD 发布阻塞项 |
| RS Grid 搜索历史和过滤 | `mods/rs/*`、`recentsearch/*`、`GridViewImplSearchMixin`、`SearchWidgetHistoryMixin`、`TagGridFilterMixin`、`ModGridFilterMixin`、`TooltipGridFilterMixin` | RS GridScreen | 无 BD 等价 UI | 不迁移到核心；只由 RS Mixin plugin 加载 |
| RS Grid 机器快捷栏 | `MachineFavoritesClient`、`GridScreenMachineTabMixin` | RS Grid extra area、RS 收藏包 | 明确暂不接入 BD | RS 快捷栏保留；BD 走独立终端中心 |
| RS 原生自动合成 Mixin | `CraftingManagerMixin`、`CraftingTaskMixin`、`CraftingGridBehaviorMixin`、`ItemGridHandlerMixin`、`NetworkItemUseMixin`、`StackUtilsMixin` | RS autocrafting API | BD 不使用 | 作为 RS legacy 白名单，不得成为 BD 递归入口 |
| 共振磁盘/共振背包/被动效果 | `resonance/disk/*`、`resonance/backpack/*`、`resonance/passive/*`、`RSInventoryBridge`、`PassiveEffectEngine` | RS storage disk、RS `INetwork` | 无 BD 等价设计 | 明确 RS-only，不宣称兼容 BD |
| RS 返回 Grid 导航 | `GuiNavStack`、`ReturnToRSPacket` | RS GridScreen、RS sidepanel channel | BD 不适用 | BD 入口不得调用；返回终端由 BD 独立实现 |
| 精妙背包 Deposit Upgrade 对 RS 控制器自动存入 | `mixin/sophisticatedbackpacks/InventoryInteractionHelperMixin` | `GridBlockEntity`、`GridNetworkNode`、RS `INetwork`、RS INSERT 权限 | 无 BD 等价实现 | 明确 RS-only；不要与通用 Pickup/Refill/Feeding 混淆 |
| RS 专用测试命令 | `command/StorageTestCommand` | RS backend ID、RS 网络解析 | 非游戏功能 | 仅作为 RS 诊断命令，不能用于判断 BD 能力 |

### 10.2 已发现的 BD 兼容缺口

#### A. 机器中心快速操作已增加 BD 独立 packet，仍需实机验收

客户端入口：

- `sidepanel/client/MachineTabHandler.onCollect`
- `sidepanel/client/MachineTabHandler.onInsert`
- `sidepanel/client/MachineTabHandler.onUnbind`

发送的包：

- `MachineCollectPacket`
- `MachineInsertPacket`
- `UnbindMachinePacket`
- `BeyondDimensionsMachineCollectPacket`
- `BeyondDimensionsMachineInsertPacket`
- `BeyondDimensionsUnbindMachinePacket`

RS 注册位置：

- `sidepanel/RSSidePanelNetworkHandler.register()`

RS 注册条件：

- `RSSidePanelModule.isEnabled()` 返回 true；
- `refinedstorage` 已加载；
- `ENABLE_RS_SIDE_PANEL` 已开启。

BD 独立 packet 已在 `BeyondDimensionsMachineNetworkHandler.register()` 中注册，客户端 `MachineTabHandler` 会根据 BD 绑定分流到这些 packet。因此这不再是“必须拆包/新增 packet”的待开发缺口；当前待办是验证 BD-only 下的权限校验、机器槽位操作、网络插入余量、解绑后的同步和失败恢复。`toRS` 仍保留在 RS/BD 快速收取接口中，后续可再统一为后端引用语义，但不应据此把 BD packet 标记为未注册。

#### B. 递归计划界面的打开机器按钮已增加 BD 分流

入口：

- `crafting/plan/CraftingPlanScreen.onOpenMachine`

当前行为：

- 根据当前绑定物品识别后端；
- RS 绑定发送 `OpenBoundMachineGuiPacket`；
- BD 绑定发送 `BeyondDimensionsOpenBoundMachineGuiPacket`。

剩余风险：

- 客户端绑定缓存或背包状态过期时需要实机验证；
- 失效绑定、跨维度和终端关闭后的返回路径需要回归。

当前实现使用已有 RS/BD 两套 packet，后续若需要统一协议再评估 `OpenBoundMachineGuiRequest`，不作为本轮阻塞项。

#### C. JEI layout 的机器 GUI 附加按钮已增加 BD 分流

入口：

- `mixin/jei/RecipeGuiLayoutsMixin` 约 405 行附近；
- anvil 分支约 1559 行附近。

当前行为：

绑定 BD 终端时允许生成按钮，并发送 `BeyondDimensionsOpenBoundMachineGuiPacket`；RS 绑定仍沿用 RS 侧栏 packet。

剩余工作是验证 BD-only 下按钮生成、跨维度打开和失效绑定提示；递归合成自定义加号与机器 GUI 按钮仍保持分开处理。

#### D. Iron Furnaces 绑定刷新已增加 BD-only 同步路径

入口：

- `mods/ironfurnaces/IronFurnaceBindingUpdater`

当前在绑定模式变化后按安装组合调用：

```java
BeyondDimensionsMachineOperations.sendBindingSync(player)
// 无 BD 时才调用 RSSidePanelNetworkHandler.sendBindingSync(player)
```

BD-only 不再强制发送 RS 侧栏同步包；BD+RS 时由混合同步逻辑保留两类绑定，RS-only 继续使用原 RS 同步。剩余工作是实机验证铁炉类型切换后的客户端刷新。

### 10.3 需要继续审计的 legacy fallback

以下不是自动判定为 RS-only，但代码中仍保留 RS 类型或 RS 解析。只有 endpoint 已经注入时，才能算 BD 可用：

| 范围 | 代表文件 | 必须确认 |
|---|---|---|
| 通用递归解析 | `CraftPacketUtils`、`CraftingResolver`、`ResolutionContext` | BD endpoint 已选时不得调用 RS 默认解析覆盖它 |
| 异步图执行 | `AsyncCraftChain`、`GenericCraftPacket`、`GenericBatchDelegate` | 每个 delegate 都继承同一个 `CraftStorageEndpoint` |
| 账本和退款 | `ExtractionLedger`、`PlayerUtils`、`TrackedNetworkInsertion` | 退款回原 endpoint，不得无条件落入 RS |
| Vanilla/Iron Furnaces | `VanillaMachineBatchDelegate`、`IronFurnacesBatchDelegate` | 工厂多槽、燃料和失败清理使用 endpoint |
| Botania | `PureDaisyBlockConversionDelegate`、Mana/Petal/Terra/Elven delegates | Pure Daisy 等世界交互不以 `network != null` 作为唯一门禁 |
| Forbidden/Goety/Eidolon | `FaCraftPacket`、`FaBatchDelegate`、`GoetyBatchDelegate`、`EidolonCraftPacket` | 特殊 starter、祭坛物品、辅助检查器必须走 endpoint |
| Embers | `EreAlchemyBatchDelegate`、`EreAlchemyInferDelegate` | pedestal、tablet、燃料和退款不能重新解析 RS |
| Aetherworks/Aether | `AetherworksBatchDelegate`、`AetherworksToolStationBatchDelegate`、`AetherFurnaceBatchDelegate` | endpoint 不为空时禁止 RS fallback |
| Touhou Little Maid | `TlmAltarBatchDelegate` | P 点/祭坛材料快照和退款必须使用选定 backend |
| Lychee catalysts | `LycheeVirtualCatalysts` | BD snapshot 路径与共振盘 RS 路径分离 |
| FTB Quests | `NativeItemTaskSubmissionService`、`QuestSubmissionEscrow` | endpoint、库存优先、部分提交和退款保持同一后端 |
| Sophisticated Backpacks 新路径 | `StorageBackpackUtils`、Pickup/Refill/Feeding/Magnet mixin | `StorageReference` 的 backend ID 必须保留；旧 RS NBT 只作为兼容格式 |

审计规则：在 endpoint 已存在时，任何 `resolveNetworkFromPlayer`、`RSIntegrationNetwork`、`CraftStorageEndpoints.legacyNetwork` 或 `INetwork` 访问都必须是明确的 RS fallback，且不能改变已选择的 backend。

### 10.4 已确认不是 RS-only 的功能

以下功能不要因为类名或旧注释含有 RS 而重复迁移：

- `CraftStorageEndpoint`、`StorageSession`、`StorageSnapshot`、`StorageReference`；
- 递归合成计划、材料账本、图执行和中间产物；
- BD/RS 物品快照、模拟提取、正式提取、插入和退款；
- 一键吃服务端执行；
- 容器 F/G 转移中的 BD mode；
- 机器 delegate 主链的 endpoint 版本；
- 次元磁铁、Pickup、Refill、Feeding、Restock 的新 backend-neutral 路径；
- FTB Quests endpoint escrow；
- BD 终端绑定、范围绑定的通用绑定流程；
- 缺货材料中键加入 JEI 收藏；
- BD 机器中心的本地绑定扫描、图标和基础远程打开入口。

这些功能仍需要实机回归，但不应重新写成一套 BD 专用业务逻辑。

## 11. 后续实施顺序

### P0：先修注册边界

1. 机器中心快速插入、取出、解绑已具备 BD 独立 packet 和客户端分流；剩余为实机验收。
2. `toRS` 语义统一为后端引用仍可后续优化，但当前 BD packet 已独立注册，不阻塞本轮。
3. BD-only 打开机器 GUI packet 已注册，`CraftingPlanScreen` 已完成 RS/BD 分流。
4. JEI layout 和 anvil 机器 GUI 按钮已完成 RS/BD 分流。
5. Iron Furnaces 绑定刷新已按安装组合选择 BD/RS 同步路径；侧栏 UI 本身继续保持 RS-only。

### P1：清理旧入口

1. **源码审计已完成第一轮**：目标 delegate 中的 `resolveNetworkFromPlayer` 只在 endpoint 为空时作为 RS fallback；`CraftStorageEndpoints.legacyNetwork` 对 BD endpoint 保持空值，并有自动化隔离测试。
2. **endpoint 继承已修复并通过编译/测试**：Goety、Forbidden、Eidolon、Embers、TLM、Iron Furnaces、Crabber's Delight、Youkai Homecoming、Wizard's Reborn 的预留、提交、燃料/能量、产物和失败回收路径均保留选定 endpoint；所有已确认的回收门禁已从单独检查 `network != null` 改为检查 `storageEndpoint()`。
3. **公共路径的失败保护已补齐，双网络实机矩阵仍待完成**：FTB escrow、容器转移、背包升级策略和 endpoint 所有权已有测试；背包本地填充/进食/补货异常会回到原 `StorageReference`，不会静默丢失已提取物；FTB 原生提交在 RS 无提取权限时会回退玩家背包，不会绕过权限继续走 endpoint。仍需在 RS+BD 同时存在时验证 FTB 提交/退款、容器 F/G、Pickup/Refill/Feeding/Magnet/Restock 不串库。
4. **通用翻译键已统一**：Eidolon/TLM 使用 `rsi.generic.error.wrong_recipe_type_detail`，Embers 的 pedestal 错误改用已有键；RS-only 文案仍保留 RS 语义，语言键集合测试通过。

### P2：可选增强

1. 为 BD 终端实现独立的机器快捷栏和状态同步。
2. 为 BD 终端实现配方树的机器跳转和收藏路径。
3. 如确实需要，让精妙背包 Deposit Upgrade 支持 `StorageReference`；这应是新功能，不应直接复制 RS Grid 代码。
4. 评估是否为 BD 实现独立的侧栏 UI；当前明确不实施，该工作不属于核心递归迁移。

## 12. 交接时必须提供的证据

下一位开发者完成一项修改后，应在报告中记录：

- 修改的文件和 packet ID；
- BD-only、RS-only、RS+BD 三种组合下的行为；
- 是否改变了 `NetworkHandler` 的 packet 注册顺序或协议版本；
- 是否仍存在 `INetwork` 作为公共方法参数；
- 失败退款的目标 backend 和 network ID；
- 对应日志关键字和测试结果；
- `compileJava`、`jar`、`git diff --check` 结果。

禁止只凭以下证据宣称兼容完成：

- BD 能显示库存；
- 配方树能打开；
- 机器 GUI 能打开；
- 源码中出现 `CraftStorageEndpoint`；
- RS 不在当前玩家背包中。

必须证明真实的提取、投料、等待、回收、退款和后端选择没有串库。

## 13. 最终交接结论

当前项目可以交接为：

> **核心存储后端抽象和 BD 物品存储适配已经完成，核心递归执行链已具备 RS/BD 二选一基础；RS 侧栏明确暂不接入 BD，RS Grid、共振系统、精妙背包 Deposit Upgrade，以及机器中心/递归计划中的少数 RS packet 入口仍不属于 BD 等价范围或尚有缺口。**

下一停点不应重新迁移 delegate：P1 的源码审计、endpoint 继承和翻译清理已经完成。应直接执行第 7 节的 BD-only、RS-only、RS+BD 安装组合验收，重点覆盖机器中心 packet 权限/退款、计划与 JEI 打开机器、FTB 提交、容器 F/G、背包 Pickup/Refill/Feeding/Magnet/Restock，以及终端关闭或绑定失效后的恢复路径。
