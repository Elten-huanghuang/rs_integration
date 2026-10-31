# Goety 兼容说明

本文记录 RSI 内置 Goety 坩埚集成的编译兼容性和功能边界。这里列出的验证结果不等于对
Goety 整个版本系列的兼容承诺。

## 已验证版本

以下 Goety 版本已在 Minecraft 1.20.1 / Forge 开发环境中通过 `compileJava`：

| Goety 版本 | 结果 |
|---|---|
| 2.5.36.1 | 通过 |
| 2.5.52.4 | 通过 |
| 2.5.60.1 | 通过 |

当前构建会从 `libs` 中选取版本号最高的 Goety jar 作为编译依赖，并从编译 classpath
中排除其他 Goety 版本。可用以下命令指定本地已有的版本：

```powershell
./gradlew.bat compileJava --project-prop=goetyCompileVersion=2.5.36.1
```

指定版本必须与 `libs` 中恰好一个 Goety jar 的版本号匹配。版本选择只控制开发编译
依赖；运行游戏时仍应安装一个与当前整合包相匹配的 Goety 版本。

## 配方支持范围

RSI 自定义坩埚目录处理药剂酿造配方。Goety 2.5.60.1 中新增的原生 `goety:cauldron`
配方以及 `CRAFTING` / `CRAFTED` 状态仍由 Goety 原生交互处理，目前不会加入 RSI
自定义远程目录，也不会由 RSI 自动化执行。

坩埚状态、配方 ID、玩家版 `brew` 方法和灵魂烛台接口通过可选适配读取，以便在不同
Goety 版本间避开编译期对新增成员的硬依赖。具体游戏行为仍应使用目标整合包中的
Goety 版本进行验证。
