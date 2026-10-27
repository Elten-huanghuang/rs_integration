# 第三方组件说明

RS Integration 不通过单一项目许可证重新授权第三方组件。第三方组件继续适用其各自许可证。

## 发布 jar 中的组件

当前发布 jar 只允许通过 jar-in-jar 打包以下已审计组件：

| 组件 | 版本 | 许可证 | 许可文件 |
|---|---|---|---|
| `io.github.llamalad7:mixinextras-forge` | `0.3.6` | MIT | `META-INF/licenses/MIT-MixinExtras.txt` |

`verifyReleaseJar` 会拒绝新增未登记的 jar-in-jar 依赖，以及打包进主 jar 的非
`com/huanghuang/rsintegration/` 类。

## 仅编译或测试使用的模组

`libs/` 中的其它模组 jar 和 Maven 依赖用于编译、测试或开发运行，不会仅因出现在
`libs/` 中而自动进入发布 jar。它们仍是各自作者的版权作品，适用各自许可证。

“可以编译”不代表“允许重新分发”。新增集成、升级依赖或改变打包方式时，必须同时确认：

- 依赖是否实际进入发布 jar；
- 许可证是否允许当前分发方式；
- 是否需要在 `META-INF/licenses/` 增加许可和归属文件；
- 是否引入了静态链接、shade、源码复制或 GPL/AGPL 等额外义务。

本说明记录工程边界和已审计的发布内容，不替代针对具体版本的正式法律审查。
