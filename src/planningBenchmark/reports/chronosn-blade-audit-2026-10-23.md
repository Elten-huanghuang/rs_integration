**魔改 chronosn_blade 递归规划修复 · 2026-10-23**

本轮针对 `prinegorerouse:chronosn_blade` 的同物品 ID、多层 NBT 刀链断层修改代码。依据是用户整合包的 OpenLoader 覆盖配方，以及本机实际安装的 SlashBlade Resharped 实现。模组 JAR 用于确认条件匹配与合成继承规则，配方内容以整合包覆盖文件为准。

本轮承接前两轮未提交的规划优化。工作区原有改动保留，未替换游戏目录中的 JAR，未修改整合包配方或存档。

**实际样本**

覆盖文件：[chronosn_blade.json](D:/E/桌面/游戏/PCL/.minecraft/versions/3.14.0/config/openloader/data/chapter_of_yuusha_3/data/prinegorerouse/recipes/chronosn_blade.json)。

目标使用 8 把不同刀，每把均要求杀敌数至少 1000、精炼至少 10，另加 `yuusha:soul_stone_2`：

- `ak:rage`、`ak:greedy`、`ak:lust`、`ak:lazy`
- `ak:arrogant`、`ak:jealous`、`ak:tai_ti`、`shizuku:teleport_blade`

其中一条实际依赖链为：

```text
chronosn_blade → ak:rage → shizuku:tsubame_blade → ak:child
              → slashblade:fox_black → slashblade_addon:dark_raven
              → slashblade:rodai_netherite → slashblade:rodai_stone
```

原配方沿链的门槛逐层降低，但上游要求的更高数值仍需由原料继承。例如，中间配方只要求 800/8，下游却要求产物 1000/10，规划必须向更深层传递 1000/10 的需求。不能仅把展示产物改成 1000/10 后仍允许使用 800/8 的库存。

`shizuku:teleport_blade` 对 `slashblade:yamato` 还要求 `broken` 和 `sealed`。这些状态不能用数值继承代替。

本地规则核对对象为 `SlashBladeResharped-1.20.1-1.8.62.jar` 中的 `SlashBladeIngredient`、`RequestDefinition`、`SlashBladeShapedRecipe`、`SwordType`。反汇编诊断文件保存在 `build/planning-audit/slashblade-*.txt`，执行 `clean` 会删除这些临时资料。

**断层与候选膨胀的原因**

1. 纯图曾把自定义刀条件投影为整份 NBT 精确相等。条件展示栈与产物的模型、UUID、额外状态或实际数值不同，就无法连接生产者。
2. 静态产物没有体现合成继承的杀敌数、魂与精炼。上游要求超过当前配方最低输入门槛时，也没有继续向原料反传。
3. 兼容候选路径曾在原生条件拒绝后，仅凭相同 item ID 接受其他刀名，形成错误候选和多余回溯；兼容步骤还会把刀的条件展示栈转换成严格 NBT 输入，重新造成断层。
4. 平面预览树按完整展示 NBT 连边，即使规划步骤已经存在，父输入展示栈与子产物仍可能不同。

以上结论来自本地代码及可复现回归。提供的日志没有找到该目标的 `[RSI-plan]` 请求记录，因此不能把日志当作该次点击的完整调用链或修复后的实服结果。

**实现与边界**

- [SlashBladePlanning.java](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/mods/slashblade/SlashBladePlanning.java) 将已确认的刀条件转换为纯值：物品相同、刀名相同，杀敌数/魂/精炼达到下限；支持 `broken`、`sealed`、`fierceredge`、`souleater`，并保留 `Unbreakable` 排除断刀的规则。`slashblade:none` 表示无名刀，不代表任意刀。
- [ImmutableRecipeGraphProjector.java](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/planning/ImmutableRecipeGraphProjector.java) 使用独立的 `SLASHBLADE` 匹配模式。输入满足后，产物杀敌数与魂采用合成累加下限，精炼采用两种配置均保证的最大值下限。`constrainedNbt` 标记阻止这些下限被当成完整、精确的产物 NBT。
- 对只有一个消耗型刀输入、数量为 1 的升级，纯图和兼容候选都可反传更高的数值门槛。执行步骤保留该目标状态，`GenericBatchDelegate` 与 `FlatCraftExecutor` 取料时继续使用提高后的条件。实际产物仍调用原配方 `assemble`。
- [StepExecutor.java](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/StepExecutor.java) 在拔刀剑工作台规划中保留条件 predicate，避免将条件展示栈锁成严格 NBT；普通机器的物理槽位逻辑保持原有处理。
- [CraftPreviewSteps.java](D:/sd/rs-integration/src/main/java/com/huanghuang/rsintegration/crafting/batch/CraftPreviewSteps.java) 仅对已规划的刀步骤建立“物品 + 刀名”索引，并验证包括反传门槛在内的条件后连接展示输入。输入数量与网格空位保留，不修改原始物品栈。
- NBT 解析复用现有有界请求/代际缓存。错误刀名在专门化前拒绝，普通配方失败候选不额外序列化成刀用 MaterialKey。没有新增后台线程或无界全局缓存。

非空附魔条件、尚未建模的刀类型和未知条件不进入纯值投影，继续保留兼容解析。多刀输入的产物若要求超过已保证的总下限，本轮不猜测应向哪把刀分配额外数值。精炼累加配置下可能存在可行但未被保守下限识别的路线。这些属于覆盖范围限制，不应通过放松条件来掩盖。

**回归证据**

| 场景 | 结果 |
|---|---|
| 魔改链样本，43 条配方，旧 EXACT 投影对照 | 无法找到第一层生产者，规划步骤为 0 |
| 同样样本，修复后，底层达标刀缺失 | 缺料预览保留 40 个步骤、8 条入口分支，5 组未满足需求；搜索统计为 19 个展开状态 |
| 8 把不同名字的达标刀直接在库存中 | 成功；不能用另一把同 ID、不同刀名的刀顶替 |
| 添加 100,000 条无关物品配方 | 依赖裁剪后仍保留相同 43 条配方，搜索状态少于 1000 |
| 添加 5000 个同物品 ID、不同刀名产物，连续查询 3 个数值门槛 | 每次仅得到正确的 1 个候选；NBT 解析总次数不超过 5010，缓存字符计费不超过限额 |
| 两层单刀升级反传 | 高数值库存可用；低数值库存不可用，缺料时仍保留多层预览 |
| 兼容候选 → 真实 StepExecutor 规划/预留 → 步骤状态 | 900/9 库存保留，使用 1200/12 库存满足 1000/10；只有低库存时失败并回滚，步骤保留执行所需状态 |
| 预览 → PlanResponse → PlanTreeModel | 多层树正确连接；错误刀名、低门槛产物不建立假边 |

样本文件：[chronosn-pack-fixture.json](D:/sd/rs-integration/src/test/resources/planning/chronosn-pack-fixture.json)。样本从 OpenLoader 覆盖中提取 43 条刀链配方；测试用同一种原版物品模拟共享 ID 的刀，将非刀原料作为外部材料，并简化模型、附魔等输出字段。提取按刀名选择覆盖配方，未枚举同刀名的全部候选，也未合并所有模组原始配方。因此 40 步和 5 组缺料是该隔离样本的结果，不是整个整合包的最终生产清单。

兼容路径测试替代了第三方类型识别与运行环境，候选计算、库存条件匹配、步骤规划及回滚使用真实实现；没有启动完整 Forge 服务器或真实拔刀剑能力系统。

最终相关测试共 **1227 项，0 失败、0 错误、0 跳过**。范围包括 `crafting.*` 与 `mods.slashblade.*`；不是整个项目全部测试。编译、`verifyReleaseJar` 与 `git diff --check` 也通过，产物未部署。本次交付报告，源码修改保留在工作区。

**服务器开销与后续验证**

本轮消除错误候选和重复 NBT 解析，仍使用前两轮的倒查裁剪与有界缓存。5000 同 ID 变种验证的是解析复用和正确候选数量；10 万目录验证的是无关配方不会进入依赖搜索。二者均不是服务器 MSPT、GC 停顿或 TPS 的实测数据。

主线程特殊配方兼容求解跨 tick 分片仍未实现。预览整理与兼容解析也仍有主线程开销，不能承诺完全不影响服务器。后续若同一个 item ID 下的刀名数量继续显著增长，可考虑配方代际内的“item ID + 刀名”二级倒查索引，进一步避免遍历整个同 ID 产物桶；当前缓存减少了解析，但没有消除所有桶扫描。

2026-10-23 10:55 的现有日志另有 `fluid_container_scan` 547.94ms、对应 capture 单元 576ms，以及 18,286 entries / 14,619 pure recipes 的目录记录。这是现有版本启动期的另一条主线程路径，与目标请求缺少日志证据的情况应分开看待；本轮没有修改它，也没有拿它作为规划修复的前后性能对比。

后续实服验收应在安装包含这些修改的版本后重新加载配方，打开目标树并验证更深层展开；分别使用低数值与达标库存确认缺料/可执行判定，再对比同场景规划日志、MSPT 和 GC 数据。当前未部署，因此这些运行结果仍待验证。

前两轮报告：[第一轮](D:/sd/rs-integration/src/planningBenchmark/reports/recursive-planning-audit-2026-10-23.md)、[第二轮](D:/sd/rs-integration/src/planningBenchmark/reports/recursive-planning-round2-2026-10-23.md)。前两轮微基准数字保持原报告的场景和限制，不与本轮样本结果混合计算收益。

**复现命令**

```powershell
.\gradlew.bat --offline test --tests 'com.huanghuang.rsintegration.crafting.*' --tests 'com.huanghuang.rsintegration.mods.slashblade.*' --console=plain
git diff --check
```

最终验证日志：`build/planning-audit/chronosn-release-validation.log`。该次运行同时包含测试与构建校验，上面的复现命令只运行测试。
