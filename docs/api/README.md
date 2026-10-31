# RSI Public API

RS Integration 为第三方模组提供三套 Java API，本分支另附两项扩展：

| API | 用途 | 入口 |
|---|---|---|
| Integration API | 让 RSI 识别你的配方、机器和特殊执行流程 | `RSIIntegrationApi` |
| Resonance Inventory API | 把共振盘内容作为额外的玩家物品来源读取或提取 | `RSIResonanceApi` |
| Consumer API | 查询 RSI 配方目录、检查材料、提交订单、查询状态和取消订单 | `RSICraftConsumerApi` |
| Machine GUI API（本分支扩展） | 为有原生交互副作用的机器注册远程菜单 | [`RSIMachineGuiApi`](MACHINE_GUI_API.md) |
| Storage API（本分支扩展） | 访问玩家授权的默认存储网络 | [`RSIStorageApi`](STORAGE_API.md) |

完整接入说明见 [RSI_INTEGRATION_API_GUIDE.md](RSI_INTEGRATION_API_GUIDE.md)。

第一次接入时，建议直接从
[实例索引](RSI_INTEGRATION_API_GUIDE.md#实例索引) 开始，按普通机器、处理进度机器、
自定义执行、共振盘物品访问或 Consumer 下单选择对应参考实现，再阅读完整契约。

公共 API 的有限互操作授权已并入唯一的项目许可证 [LICENSE](../../LICENSE)。
RSI 主体仍是专有软件；该许可只允许第三方针对公共 API 编译、运行和发布兼容
模组，不授权复制或修改 RSI 实现。

## 先选择接入方式

| 你的需求 | 需要实现 |
|---|---|
| 只让其他模组查询或下单 RSI 已有配方 | Consumer API |
| 你的物品或工具需要把共振盘内容作为额外物品来源 | `RSIResonanceApi` |
| 你的普通配方使用固定槽位、固定流体罐和标准输入输出 | `RecipeIntegration`、`MachineIntegration` |
| 你的机器有 GUI、仪式、多方块、私有资源或无法用标准端口表达的流程 | 在上述基础上增加 `RecipeExecutionIntegration` |

RSI 的集成方式是 Java SPI。第三方继续使用自己的 `RecipeType`、`RecipeSerializer`、
datapack、`BlockEntity` 和 capability，不需要把配方改写成 RSI JSON。

## 当前契约

| 项目 | 值 |
|---|---|
| Minecraft | 1.20.1 |
| Java | 17 |
| Forge | 47.x |
| Integration API | 1.1.x |
| Resonance Inventory API | 1.0.x |
| Consumer API | 1.0.x |

稳定性范围仅包括以下包中被标记为公共 API 的类型：

```text
com.huanghuang.rsintegration.api
com.huanghuang.rsintegration.api.recipe
com.huanghuang.rsintegration.api.machine
com.huanghuang.rsintegration.api.machine.gui
com.huanghuang.rsintegration.api.storage
com.huanghuang.rsintegration.api.execution
com.huanghuang.rsintegration.api.resonance
com.huanghuang.rsintegration.api.consumer
com.huanghuang.rsintegration.api.diagnostic
```

其余包、实现类、网络包、内部注册表和调试入口均不是第三方契约。

新增包内带有 `@ApiStatus.Internal` 的服务安装点和适配实现同样不属于公共契约。
