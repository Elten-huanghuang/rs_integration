# RSI API 文档

## 接入者入口

从 [RSI_INTEGRATION_API_GUIDE.md](RSI_INTEGRATION_API_GUIDE.md) 开始。该指南包含：

- 注册事件和生命周期
- 线程与版本规则
- Recipe/Machine/Custom Execution 接入
- Consumer API 完整调用流程
- 自动测试和游戏内烟测案例
- 常见错误与排错顺序

真实模组烟测步骤见 [RSI_API_SMOKE_TEST_GUIDE.md](RSI_API_SMOKE_TEST_GUIDE.md)。

## 可编译示例

```text
src/examplemod/java/com/example/rsiintegration/examplemod/RSIIntegrationExampleMod.java
src/test/java/com/example/rsiintegration/PublicIntegrationExample.java
src/test/java/com/example/rsiintegration/PublicConsumerExample.java
src/apismoke/java/com/example/rsiintegration/apismoke
```

## 公共包

```text
com.huanghuang.rsintegration.api
com.huanghuang.rsintegration.api.recipe
com.huanghuang.rsintegration.api.machine
com.huanghuang.rsintegration.api.execution
com.huanghuang.rsintegration.api.consumer
com.huanghuang.rsintegration.api.diagnostic
```

`Default*`、`*Services` 和其他标记 `@ApiStatus.Internal` 的类型不属于第三方接入面。

## 验证命令

```text
gradlew test --tests "com.huanghuang.rsintegration.api.*"
gradlew test --tests "com.huanghuang.rsintegration.crafting.external.*"
gradlew test --tests "com.example.rsiintegration.apismoke.*"
gradlew compileExamplemodJava
gradlew apismokeClasses
```

发布 jar 前再运行：

```text
gradlew verifyReleaseJar
```
