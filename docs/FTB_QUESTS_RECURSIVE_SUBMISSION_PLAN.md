# FTB Quests 递归提交集成计划书

## 一、目标体验

以一个任务同时要求：

- A × 16
- B × 8

为例，期望流程如下：

1. 玩家在 FTB Quests 中打开 A 对应的物品任务。
2. 点击 A，跳转到 JEI。
3. JEI 除了普通制作配方外，还能显示一条 **FTB 任务提交**记录：
   - 输入：A × 16、B × 8；
   - 目标：完成对应任务；
   - 展示任务进度、是否消耗物品、是否可重复以及奖励。
4. 玩家点击现有递归合成按钮。
5. 递归合成计划显示：
   - 合成缺少的 A；
   - 合成缺少的 B；
   - 最终执行“提交任务”；
   - 提交成功后自动领取可自动处理的奖励。
6. 玩家确认后：
   - 从 RS 网络和玩家物品栏使用已有材料；
   - 自动制作缺少的 A、B；
   - 在服务端按 FTB Quests 原生规则提交；
   - 自动领取奖励；
   - 对可重复任务，按用户选择的次数逐轮执行。

---

## 二、第一阶段支持范围

### 2.1 消耗型物品任务

```java
itemTask.consumesResources() == true
```

行为：

- 自动制作缺少物品；
- 最终真正消耗任务所需物品；
- 推进任务进度；
- 所有必需任务完成后领取奖励。

这是最接近“递归合成并提交”的核心场景。

### 2.2 非消耗型检测任务

```java
itemTask.consumesResources() == false
```

行为：

- 确保玩家或 RS 网络中存在任务要求的物品；
- 根据 FTB Quests 原生规则推进进度；
- 不销毁物品；
- 完成后领取奖励。

项目中已经存在非消耗任务进度桥：

- `src/main/java/com/huanghuang/rsintegration/compat/ftbquests/FtbQuestExternalItemDetector.java`
- `src/main/java/com/huanghuang/rsintegration/compat/ftbquests/ExternalItemProgressBridge.java`

它目前只负责“实际插入 RS/背包的物品推进任务”，并不负责：

- 将任务展示为递归合成目标；
- 汇总同一个 Quest 下的多个任务；
- 主动制作材料；
- 自动领取奖励；
- 可重复任务循环。

新功能可以抽取并复用其匹配规则，但不应依赖“插入事件”完成整条流程。

### 2.3 第一版明确不支持的任务

#### 任务面板专用任务

```java
itemTask.isTaskScreenOnly() == true
```

第一版应明确排除。

该任务要求通过 `TaskScreenBlockEntity` 的物品处理器提交，不等价于普通玩家提交。绕过面板直接增加进度会破坏整合包作者设定的提交方式。

后续可单独支持：

- 绑定 FTB Task Screen；
- 将任务屏幕作为一种可绑定机器；
- 通过任务屏幕自身的 `TaskItemHandler` 提交。

#### 仅制作检测任务

```java
itemTask.isOnlyFromCrafting() == true
```

第一版建议只在能够证明最终物品确实由本轮合成产生时支持，不能简单调用 `addProgress()`。

FTB Quests 原生逻辑会向 `submitTask(..., ItemStack)` 传入被制作的物品。直接伪造进度会绕开“必须由制作获得”的语义。

#### 非物品任务

第一版不接入：

- 流体任务；
- 能量任务；
- 击杀任务；
- 维度、位置和结构任务；
- 自定义任务；
- 勾选任务。

#### 选择奖励

第一版不自动处理 `ChoiceReward`，因为它需要玩家明确选择，不能自动替玩家决定。

---

## 三、FTB Quests 2001.4.13 行为确认

分析目标版本：

```text
ftb-quests-forge-2001.4.13.jar
```

### 3.1 提交入口

FTB Quests 自身的客户端提交包最终调用：

```java
task.submitTask(teamData, player);
```

`SubmitTaskMessage` 在服务端还会检查：

- `TeamData` 未锁定；
- task ID 存在；
- `teamData.canStartTasks(task.getQuest())`；
- 在 `ServerQuestFile.withPlayerContext(player, ...)` 中执行。

本集成必须保留这些检查，不能只调用：

```java
teamData.addProgress(...);
```

否则会绕开：

- 前置任务；
- 排他任务线；
- 顺序任务；
- Task 自身的提交规则；
- 完成事件和状态同步。

### 3.2 消耗型 `ItemTask`

`ItemTask.submitTask(...)` 对消耗型任务会扫描玩家主物品栏，并通过：

```java
itemTask.insert(teamData, stack, false)
```

消耗物品并增加进度。

FTB Quests 原生方法不会直接读取 RS 网络。正确接入方式是：

1. 完成递归制作；
2. 按计划从 RS 提取精确数量；
3. 将物品放入受控的提交托管区；
4. 调用原生 `submitTask()`；
5. 校验实际进度增量和实际消耗量；
6. 退回未被接受的物品。

不建议直接 `teamData.addProgress()` 后自行删除 RS 物品，否则物品扣除和任务完成可能失去事务一致性。

### 3.3 可重复任务

Quest 是否可重复由：

```java
quest.canBeRepeated()
```

决定。

完成奖励领取后，FTB Quests 通过：

```java
quest.checkRepeatable(teamData, playerUuid)
```

检查是否所有奖励均已领取，满足条件后才重置进度。

正确循环应为：

```text
制作材料
  → 提交所有 ItemTask
  → Quest 完成
  → 领取所有可处理奖励
  → quest.checkRepeatable(...)
  → 确认任务已经重置
  → 开始下一轮
```

不能在领奖前手工 `resetProgress()`，否则可能造成：

- 奖励无法领取；
- 一轮领取多次；
- 任务进度与 claimed reward 状态不一致。

### 3.4 奖励领取

FTB Quests 原生服务端领取最终使用：

```java
teamData.claimReward(player, reward, notify)
```

调用前应检查：

```java
teamData.getClaimType(player.getUUID(), reward).canClaim()
```

以及：

```java
teamData.isRewardBlocked(reward)
```

FTB Quests 自身还会处理 `RewardAutoClaim`。推荐策略是：

1. Quest 完成后，先允许 FTB Quests 原生自动领取逻辑执行；
2. 再枚举仍为 `CAN_CLAIM` 的奖励；
3. 普通确定性奖励自动领取；
4. `ChoiceReward` 暂停并提示玩家选择；
5. 被阻止或不允许领取的奖励不强行领取。

---

## 四、核心架构

### 4.1 不将任务提交伪装成普通 `Recipe`

当前递归系统主要围绕以下组件工作：

- `RecipeIndex`
- `CraftingResolver`
- `CraftPlanGraph`
- `GenericCraftPacket`
- `AsyncCraftChain`

普通配方模型是：

```text
输入材料 → 产出物品
```

任务提交模型则是：

```text
输入若干物品 + 玩家任务状态 → 改变任务状态 + 发放奖励
```

任务提交具有以下特殊性质：

- 与玩家和队伍绑定；
- 存在前置条件；
- 可能消耗，也可能仅检测物品；
- 可能重复；
- 奖励不一定是物品；
- 执行后不能像普通配方一样随意回滚；
- 同一个 Quest 可以包含多个并列 Task。

因此应新增“任务提交目标”和特殊终端节点，而不是伪造普通 Recipe 并放入 `RecipeManager`。

### 4.2 统一目标类型

建议新增：

```java
public sealed interface CraftingTarget
        permits RecipeCraftingTarget, QuestSubmissionTarget {
}
```

任务目标示例：

```java
public record QuestSubmissionTarget(
        long questId,
        long selectedTaskId,
        int repeatCount
) implements CraftingTarget {
}
```

其中：

- `questId` 是服务端唯一可信标识；
- `selectedTaskId` 记录玩家是从 A 还是 B 进入；
- 真正规划时聚合同一 Quest 下所有尚未完成的必需 `ItemTask`；
- 不信任客户端传入的物品数量、任务属性或奖励列表。

### 4.3 Quest 快照模型

建议建立独立 DTO：

```text
QuestSubmissionSnapshot
QuestItemRequirement
QuestRewardPreview
QuestEligibility
```

示例：

```java
public record QuestItemRequirement(
        long taskId,
        Ingredient ingredient,
        long required,
        long currentProgress,
        boolean consumes,
        boolean onlyFromCrafting,
        boolean taskScreenOnly,
        boolean optional
) {
}
```

服务端扫描 `ServerQuestFile`，只向客户端发送展示和规划需要的数据。

---

## 五、多物品任务聚合规则

当玩家从 A 打开 JEI 时，不能只处理 A 所属的 `ItemTask`，而应解析其父 Quest。

### 5.1 默认聚合范围

收集该 Quest 中符合以下条件的任务：

- 尚未完成；
- 非 optional，或确实是完成 Quest 所必需；
- 当前顺序允许执行；
- 类型为 `ItemTask`；
- 非 task-screen-only；
- 满足当前阶段的支持策略。

例如：

```text
Quest
├─ ItemTask A × 16
├─ ItemTask B × 8
└─ CheckmarkTask
```

第一版结果应为：

- A、B 可以加入计划；
- `CheckmarkTask` 无法自动完成；
- JEI 提交条目显示“存在手动任务，当前无法全自动完成”；
- 可以只制作 A/B，或等 Checkmark 已完成后再执行提交。

不能把包含未完成手动任务的 Quest 错误标记为“可一键完成”。

### 5.2 顺序任务

如果：

```java
quest.getRequireSequentialTasks() == true
```

则必须按 Quest 的 task 顺序逐个执行。

例如：

```text
A → B
```

即使材料都已经齐全，也必须：

1. 提交 A；
2. 等待 FTB Quests 更新状态；
3. 再提交 B。

计划界面也应显示顺序依赖，而不是把它们当成完全并行的材料需求。

---

## 六、接入递归合成规划

### 6.1 复用现有根需求解析

当前根材料需求可以通过：

```java
CraftingResolver.resolveGraphForSpecsWithTypes(...)
```

生成 DAG。

任务规划器可以将每个 Quest ItemTask 转成 `IngredientSpec`：

```text
未完成数量 = itemTask.getMaxProgress() - teamData.getProgress(itemTask)
```

然后一次性传给 resolver。

A 和 B 将成为两个根需求：

```text
QuestSubmitNode
├─ Root A × remainingA
│  └─ A 的递归配方链
└─ Root B × remainingB
   └─ B 的递归配方链
```

### 6.2 增加任务终端节点

建议为 DAG 增加特殊节点：

```java
QuestSubmitNode
```

字段示例：

```java
long questId;
List<QuestTaskPort> tasks;
int repeatIndex;
boolean autoClaim;
```

每个 task port 对应：

- FTB task ID；
- Ingredient；
- 数量；
- 是否消费；
- 是否需要“本轮制作”的来源证明。

### 6.3 区分消费、观察和制作证明

非消耗任务不能像普通根需求一样永久扣除库存。

图中应区分：

- `CONSUMED`：提交后物品消失；
- `OBSERVED`：只证明存在；
- `CRAFTED_PROOF`：必须证明由本轮制作产生。

可以扩展当前 `DemandRole`：

```java
QUEST_CONSUMED
QUEST_OBSERVED
QUEST_CRAFTED
```

否则 resolver 会把所有根需求都当作普通消耗品，导致非消耗型任务完成后物品消失。

---

## 七、执行事务设计

### 7.1 执行顺序

推荐流程：

```text
1. 服务端重新校验 Quest 状态
2. 重新计算各任务的剩余数量
3. 执行所有递归制作节点
4. 将待提交物品放入 QuestSubmissionEscrow
5. 按顺序调用 ItemTask 原生 submitTask
6. 校验每个任务的实际进度变化
7. 确认 Quest 完成
8. 领取奖励
9. 若可重复且还有轮数，等待重置后进入下一轮
```

### 7.2 提交托管区

新增：

```java
QuestSubmissionEscrow
```

职责：

- 记录从 RS 和玩家背包取得的每一份材料；
- 在调用 FTB Quests 前暂时托管；
- 只将当前 task 需要的材料暴露给原生提交路径；
- 提交后核对实际减少量；
- 将未使用物品退回 RS；
- 失败时优先退回尚未提交的材料；
- 防止 FTB Quests 扫描并误吃玩家背包中不属于本次计划的同类物品。

推荐步骤：

1. 记录玩家背包原有的匹配物品；
2. 将本次提交的精确数量插入临时位置；
3. 调用 `itemTask.submitTask(teamData, player)`；
4. 根据物品变化和进度变化计算实际消费；
5. 清理剩余的临时物品；
6. 将剩余物品退回网络。

如果无法可靠区分同类原有栈，应先托管玩家原有的匹配物品，在提交结束后原样恢复。

### 7.3 失败语义

提交前的制作阶段可以沿用当前 ledger 和退款机制。

一旦某个 FTB Task 的进度已经推进，就属于外部已提交状态，不能假装完全回滚。

建议执行状态：

```java
PREPARING
CRAFTING
READY_TO_SUBMIT
SUBMITTING
COMMITTED
CLAIMING
COMPLETE
PARTIAL_FAILURE
```

规则：

- `SUBMITTING` 前失败：正常退款；
- 某个 task 已推进后失败：
  - 不回退 FTB Quest 进度；
  - 退还尚未提交的材料；
  - 明确报告“部分提交”；
  - 下次重新规划时读取实际剩余进度并继续完成。

---

## 八、JEI 展示设计

### 8.1 新增 JEI 分类

建议注册：

```text
rs_integration:ftb_quest_submission
```

标题：

```text
FTB 任务提交
```

一条展示记录对应一个 Quest，而不是一个 ItemTask。

布局示例：

```text
[任务图标] 任务名称

需要：
[A ×16] [B ×8]

当前进度：
A 4/16
B 0/8

模式：
消耗提交 / 物品检测
一次性 / 可重复

奖励：
[奖励1] [奖励2] [? 选择奖励]

[递归合成按钮]
```

### 8.2 从 A 或 B 查到任务

任务提交记录将 Quest 中每个有效 `ItemTask` 的：

```java
itemTask.getValidDisplayItems()
```

注册为 JEI 输入。

这样查看 A 的用途时，能够看到：

```text
A → FTB 任务提交
```

查看 B 的用途时也能看到同一条任务记录。

FTB Quests 自身对普通物品点击会调用：

```java
FTBQuests.getRecipeModHelper().showRecipes(stack)
```

这通常打开物品的制作方法。任务提交记录在 JEI 语义上更适合作为物品的“用途”。

推荐交互：

- 普通点击：查看 A 的制作配方；
- 查看用途：显示 A 参与的 FTB Quest；
- FTB Quest 页面增加“在 JEI 中查看任务提交”入口，直接打开任务提交分类。

### 8.3 动态刷新

任务状态依赖：

- 当前玩家；
- 当前队伍；
- 当前进度；
- 前置任务状态；
- 排他任务线；
- 奖励领取状态。

因此不能只在 JEI 启动时生成一次。

需要在以下时机刷新：

- 客户端收到任务进度更新；
- 玩家切换队伍；
- Quest 文件重载；
- 任务完成；
- 奖励领取；
- 可重复任务重置；
- JEI 打开时发现缓存版本过旧。

建议使用以下缓存键：

```text
Quest ID + Team ID + progress revision
```

---

## 九、递归计划界面改造

现有计划界面为：

```text
src/main/java/com/huanghuang/rsintegration/crafting/plan/CraftingPlanScreen.java
```

当前确认执行默认通过 `recipeId` 发送 `GenericCraftPacket`。任务提交应使用独立 packet，避免继续在普通配方 packet 中堆叠特殊分支。

建议新增：

```java
QuestPlanRequestPacket
QuestPlanResponsePacket
ExecuteQuestSubmissionPacket
```

计划界面可以复用：

- 材料列表；
- 卡片视图；
- 树视图；
- 配方分支选择；
- 缺料展示；
- 重复次数输入。

标题和终端卡片改为：

```text
完成任务：Quest 名称
```

末端不显示普通物品产出，而显示：

```text
提交 A、B → 完成任务 → 自动领取奖励
```

### 9.1 可重复次数

任务重复次数不能简单处理成：

```text
A 需求 × repeat
B 需求 × repeat
```

因为每轮都必须经历：

```text
完成 → 领奖 → 重置
```

规划可以汇总总材料量，但执行必须逐轮提交。

界面示例：

```text
重复完成：5 次
总材料：
A × 80
B × 40

每轮完成并领奖后才会开始下一轮
```

如果奖励中可能包含下一轮所需材料，第一版不要将奖励作为后续轮次的可用材料，以保持规划确定性。

---

## 十、自动领奖策略

建议增加配置：

```toml
enableFtbQuestRecursiveSubmission = true
autoClaimQuestRewards = true
autoClaimChoiceRewards = false
maxRepeatableQuestRuns = 64
allowNonConsumingQuestTasks = true
allowOnlyFromCraftingTasks = false
```

领奖规则：

| 奖励类型 | 第一版行为 |
|---|---|
| `ItemReward` | 自动领取 |
| `XPReward` / `XPLevelsReward` | 自动领取 |
| `CommandReward` | 自动领取，由服务端执行 |
| `LootReward` / `RandomReward` | 自动领取 |
| `AdvancementReward` | 自动领取 |
| `StageReward` | 自动领取 |
| `ChoiceReward` | 暂停，要求玩家选择 |
| `CustomReward` | 默认不自动，除非确认兼容 |
| 被 reward blocking 阻止 | 不领取并提示 |
| `getClaimType() != CAN_CLAIM` | 跳过 |

对于可重复 Quest，如果存在无法自动领取的奖励，任务不会重置。重复链必须暂停，不能持续重试形成死循环。

---

## 十一、服务端校验与并发控制

所有关键状态必须由服务端重新读取。

客户端只能发送：

```text
questId
期望重复次数
配方分支选择
```

服务端校验：

- 玩家不是 FakePlayer；
- `ServerQuestFile.INSTANCE` 可用且未加载中；
- `TeamData` 存在且未锁定；
- Quest 和 Task ID 存在；
- `teamData.canStartTasks(quest)`；
- Quest 当前未被排他分支排除；
- 任务仍未完成；
- task 类型和属性仍符合支持范围；
- 可重复次数未超过配置上限；
- 每轮开始时任务确实已被 FTB Quests 重置；
- 奖励状态真实可领取。

客户端不能直接提交：

- Ingredient；
- 所需数量；
- `consumesResources`；
- 奖励类型；
- 当前进度。

多人队伍中，两个队友可能同时规划和执行同一个 Quest。执行提交时需要以：

```text
teamId + questId
```

建立服务端短期互斥锁，并在锁内重新读取任务进度。

---

## 十二、建议代码结构

```text
compat/ftbquests/
├─ FtbQuestIntegration.java
├─ FtbQuestSubmissionScanner.java
├─ FtbQuestSubmissionEligibility.java
├─ FtbQuestSubmissionPlanner.java
├─ FtbQuestSubmissionExecutor.java
├─ FtbQuestRewardClaimer.java
├─ QuestSubmissionEscrow.java
├─ QuestSubmissionSnapshot.java
├─ QuestItemRequirement.java
└─ client/
   ├─ FtbQuestJeiPlugin.java
   ├─ FtbQuestSubmissionCategory.java
   ├─ FtbQuestSubmissionRecipe.java
   └─ FtbQuestJeiCache.java

crafting/target/
├─ CraftingTarget.java
├─ RecipeCraftingTarget.java
└─ QuestSubmissionTarget.java

network/ftbquests/
├─ QuestPlanRequestPacket.java
├─ QuestPlanResponsePacket.java
└─ ExecuteQuestSubmissionPacket.java
```

不建议继续扩大 `FtbQuestExternalItemDetector` 的职责。它目前只负责外部插入事件推进非消耗任务。新功能应保持独立，最多抽取共享的：

```java
FtbItemTaskMatcher
```

避免一个类同时负责插入检测、JEI 索引、计划、提交和领奖。

---

## 十三、分阶段实施

### 阶段 1：只读扫描与资格判断

完成：

- 扫描当前玩家可开始的 Quest；
- 聚合同一 Quest 下的多个 `ItemTask`；
- 区分一次性和可重复；
- 区分消费、非消费、制作限定和面板限定；
- 生成不可变 snapshot；
- 输出无法自动化的具体原因。

验收：

- A+B 正确聚合；
- 未解锁 Quest 不出现；
- 已完成的一次性 Quest 不出现；
- 可重复 Quest 领奖并重置后重新出现；
- task-screen-only 明确排除。

### 阶段 2：JEI 虚拟提交分类

完成：

- 注册 `ftb_quest_submission` 分类；
- A/B 都能查到同一个 Quest；
- 展示进度、奖励、重复性和限制；
- 添加递归计划按钮。

验收：

- 从 A 的用途进入能看到任务；
- 从 B 的用途进入也能看到；
- 队伍进度变化后刷新；
- 多个 Quest 都需要 A 时显示多条任务记录。

### 阶段 3：任务计划生成

完成：

- 将剩余 ItemTask 转成多个 `IngredientSpec`；
- 调用现有 DAG resolver；
- 增加 Quest terminal node；
- 在现有计划 UI 展示任务终点；
- 支持配方分支和缺料提示。

验收：

- A 已有一部分时只规划剩余量；
- B 完全没有时递归展开 B；
- 非消耗需求不会在计划中被永久扣除；
- 顺序任务显示正确依赖。

### 阶段 4：单次消耗型提交

完成：

- `QuestSubmissionEscrow`；
- 递归制作后调用原生 `ItemTask.submitTask()`；
- 校验实际进度；
- 失败时退回未提交材料；
- 部分提交状态可恢复。

验收：

- RS 有材料时正确消耗；
- RS 缺料时先自动制作；
- 玩家背包已有同类物品不会被误吃；
- 中途断线或机器失败不会刷物、吞掉未提交物品；
- FTB Quests 完成事件正常触发。

### 阶段 5：自动领奖

完成：

- 普通奖励自动领取；
- `ChoiceReward` 暂停；
- reward blocking 尊重原规则；
- 奖励满背包行为测试；
- 领取后刷新 JEI 和计划状态。

验收：

- 每个奖励只领取一次；
- 不能领取时不强行调用；
- 部分奖励失败时状态清晰；
- 命令、经验和物品奖励行为与手动领取一致。

### 阶段 6：可重复 Quest

完成：

- 每轮提交、领取和重置；
- 重置失败时停止；
- 重复次数上限；
- 进度反馈；
- 用户取消后停止下一轮。

验收：

- 重复 5 次严格领取 5 次；
- `ChoiceReward` 阻塞时停在当前轮；
- Quest 未重置时绝不开始下一轮；
- 中途取消不会提前制作下一轮；
- 不出现重复提交或重复奖励。

### 阶段 7：非消耗和 only-from-crafting

完成：

- 非消耗观察需求；
- 本轮制作来源证明；
- 与已有 `ExternalItemProgressBridge` 去重；
- 避免同一产物同时由插入 hook 和执行器重复推进。

### 阶段 8：任务面板

作为独立后续功能：

- 可绑定 FTB Task Screen；
- 验证面板配置的 task ID；
- 向面板自身的 ItemHandler 插入；
- 支持面板专用任务；
- 保持区块加载和机器可达性规则。

---

## 十四、测试计划

### 14.1 纯逻辑单测

覆盖：

- A+B 聚合；
- optional task 规则；
- 一次性和可重复分类；
- 剩余数量计算；
- 奖励自动领取策略；
- `ChoiceReward` 阻塞；
- 重复状态机；
- 顺序任务拓扑；
- long 数量到递归合成批次的溢出保护。

### 14.2 服务端集成测试

重点场景：

1. 消耗型 A+B；
2. 非消耗型 A+B；
3. A 已完成、B 未完成；
4. 前置 Quest 未完成；
5. 顺序任务；
6. 任务中途被其他队友完成；
7. 规划后管理员重载 Quest；
8. 提交时队伍被锁；
9. 奖励背包已满；
10. 玩家提交过程中退出；
11. 可重复任务中途停止；
12. task-screen-only 不得绕过；
13. only-from-crafting 不得用库存伪造；
14. 同队多人同时点击自动提交时只能有一个提交事务成功执行。

### 14.3 守恒测试

必须验证：

```text
初始玩家物品
+ 初始 RS 物品
+ 本次真实制作产物
+ 任务奖励
=
结束玩家物品
+ 结束 RS 物品
+ 任务实际消费
```

重点测试：

- FTB progress 已推进但领奖失败；
- A 提交成功、B 提交失败；
- 重复任务第 3/5 轮失败；
- 服务器关闭；
- `AsyncCraftChain` abort；
- 奖励本身包含 A 或 B。

---

## 十五、主要风险

### 15.1 Quest 不是静态配方

JEI 条目依赖当前玩家和队伍状态，不能像普通 `RecipeIndex` 一样建立全局静态缓存。

### 15.2 FTB 提交只扫描玩家背包

直接调用 `submitTask()` 不会读取 RS，因此托管和临时背包事务是必须的。

### 15.3 可重复任务与领奖强耦合

FTB Quests 在奖励满足领取条件后才允许重置。任何不能自动领取的奖励都可能阻塞下一轮。

### 15.4 多人队伍并发

多人同时提交同一 Quest 时必须使用 `teamId + questId` 互斥，并在锁内重新读取进度。

### 15.5 FTB Quests API 版本耦合

项目目前声明：

```toml
ftbquests >= 2001.4.13
```

本功能会直接依赖：

- `ServerQuestFile`
- `TeamData`
- `Quest`
- `ItemTask`
- `Reward`

建议使用直接 API 调用，不使用反射调用原版/依赖方法；同时在可选模组加载边界处隔离类加载。发现不兼容版本时，应只关闭该集成功能，不影响整个模组启动。

---

## 十六、推荐的最小可用版本

第一版建议只完成这一条闭环：

> 普通消耗型 `ItemTask`，支持同一 Quest 的 A+B 聚合、单次完成和普通奖励自动领取。

暂时不做：

- 可重复多轮；
- 非消耗任务；
- only-from-crafting；
- task-screen-only；
- `ChoiceReward`；
- 自定义奖励。

这条闭环已经可以验证最关键的四项能力：

1. JEI 虚拟提交展示；
2. 多根需求递归规划；
3. RS 到 FTB Quest 的事务化提交；
4. 自动领奖。

确认不存在吞物、刷物和重复奖励后，再加入可重复任务。可重复任务应排在自动领奖之后，因为 FTB Quests 的重置依赖奖励领取状态。

总体结论：该功能可行，现有 DAG 多根需求已经能够承载“A 和 B”。主要新增内容是 Quest 虚拟终端节点、服务端提交事务和动态 JEI 分类。
