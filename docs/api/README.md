# RSI Public API

RS Integration 为第三方模组提供两套 Java API：

| API | 用途 | 入口 |
|---|---|---|
| Integration API | 让 RSI 识别你的配方、机器和特殊执行流程 | `RSIIntegrationApi` |
| Consumer API | 查询 RSI 配方目录、检查材料、提交订单、查询状态和取消订单 | `RSICraftConsumerApi` |

完整接入说明见 [RSI_INTEGRATION_API_GUIDE.md](RSI_INTEGRATION_API_GUIDE.md)。

公共 API 的有限互操作授权见
[LICENSE-API.md](../../LICENSE-API.md)。RSI 主体仍是专有软件；该许可只允许
第三方针对公共 API 编译、运行和发布兼容模组，不授权复制或修改 RSI 实现。

## 先选择接入方式

| 你的需求 | 需要实现 |
|---|---|
| 只让其他模组查询或下单 RSI 已有配方 | Consumer API |
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
| Consumer API | 1.0.x |

稳定性范围仅包括以下包中被标记为公共 API 的类型：

```text
com.huanghuang.rsintegration.api
com.huanghuang.rsintegration.api.recipe
com.huanghuang.rsintegration.api.machine
com.huanghuang.rsintegration.api.execution
com.huanghuang.rsintegration.api.consumer
com.huanghuang.rsintegration.api.diagnostic
```

其余包、实现类、网络包、内部注册表和调试入口均不是第三方契约。
