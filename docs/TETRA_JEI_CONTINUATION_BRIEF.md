# Tetra + JEI 继续开发说明

> 2026-09-23 实现更新：用户明确要求不加按钮，打开 Tetra 加工台后直接在右侧 JEI 显示当前槽位可用材料。实现由 Tetra `MaterialData.material.getApplicableItemStacks()` 取得真实物品栈，再用当前可用方案的 `acceptsMaterial(target, slot, materialSlot, stack)` 校验；用 JEI 运行时物品管理器仅补入 JEI 原本未注册的物品，并从右侧列表中筛出候选项。离开加工台或清空目标时恢复 JEI 原列表。没有 JEI Tetra 依赖，也不要求打开全息球。
>
> 当前客户端实现位于 `mods/tetra/client/TetraWorkbenchMaterialState`，JEI 物品桥接位于 `mods/jei/TetraJeiItemBridge`，过滤入口为 `IngredientFilterTetraMixin`。通过 JEI 自身 `IIngredientManager` 添加/删除动态物品，保留普通物品栈的物品 ID 和 NBT，因此 RSI 现有物品键可映射至库存数量叠层。筛选在 JEI 现有物品列表上进行，与搜索条件取交集；只移除桥接自身注入的物品，不移除 JEI/其他模组原有物品。当前修改还需编译和游戏内验收。
>
> `compileJava`、NBT 库存映射测试、JEI Mixin 合同测试和 JAR 构建均已通过；尚未进行游戏内验收。显示候选物品不代表其当前在玩家背包或 RS 网络库存中实际拥有；RSI 的既有数量叠层仅在物品 ID 与 NBT 精确匹配库存时显示数量。下文保留原研究记录，其中按钮流程仅为历史方案，不适用于当前实现。

> 交接对象：下一位负责实现的 AI/开发者  
> 项目：`D:\sd\rs-integration`  
> 目标环境：Minecraft 1.20.1 Forge、Tetra 6.9.0、JEI 15.20.0.129、JEI Tetra 6.9.0-1.2.0-fix

## 1. 用户真正要解决的问题

用户不希望在 Tetra 加工台旁边再做一个臃肿的 RS 材料列表，也不希望手动输入材料名称。

用户希望的核心流程是：

1. 打开 Tetra 加工台。
2. 放入目标物品，例如钻石镐。
3. 点击 Tetra 的模块槽位，例如“头部（右）”。
4. 系统读取该槽位当前真正可用的 Tetra 方案和材料类型。
5. 直接让 JEI Tetra 显示这些材料的配方/获取方式。
6. 用户在 JEI 中继续查看材料来源，而不是在 RSI 自己的列表里二次搜索。

示例：

> 钻石镐 → 头部（右） → JEI 只显示可以用于这个槽位的 Tetra 材料，例如铁、钻石、下界合金以及其他模组提供的合法材料。

这更接近 Tetra 全息球的材料数据逻辑，而不是“从 RS 里搜名字”。

## 2. 截图中已经确认的界面结构

截图中的主体仍然是 Tetra 原生加工台界面：

- 中央显示目标工具和模块槽位。
- 左侧是 Tetra 方案/模块相关区域。
- 右侧是 JEI 材料列表。
- Tetra 自身使用黑色背景、白色细线和银白色边框。

后续实现不应再绘制一个大面积自定义材料仓。最理想的交互是：

- 保留 Tetra 原生界面。
- 只增加一个很小的“在 JEI 中查看当前槽位材料”入口，或在用户明确触发时执行。
- JEI 自己负责显示材料网格和材料配方。
- 不要把 Tetra 的材料显示复制一份到 RSI 面板。

## 3. 正确的数据来源

不要使用材料名称搜索，也不要只使用 RS 存储快照推断 Tetra 材料。

应该从 Tetra 当前工作台上下文取得数据：

### 3.1 当前目标物品

`WorkbenchTile.getTargetItemStack()`

例如钻石镐。

### 3.2 当前选择的模块槽位

Tetra 6.9.0 的 `WorkbenchScreen` 有客户端字段：

```text
selectedSlot
```

当用户只点击“头部（右）”时，这个值可能已经是类似下面的槽位字符串：

```text
pickaxe/head_right
```

重要：Tetra 的 `WorkbenchScreen.selectSlot(String)` 会先设置界面的 `selectedSlot`，然后调用 `WorkbenchTile.clearSchematic()`。因此在用户只选择模块槽、尚未选择具体方案时：

- `WorkbenchScreen.selectedSlot` 可能已经有值。
- `WorkbenchTile.getCurrentSlot()` 可能仍然是空值。
- `WorkbenchTile.getCurrentSchematic()` 通常为空。

不能只读取 `WorkbenchTile.getCurrentSlot()`，否则会错误提示“请先选择方案”。

### 3.3 当前可用方案

Tetra 6.9.0 提供：

```text
SchematicRegistry.getSchematics(
    ItemStack target,
    String selectedSlot,
    Player player,
    Level level,
    BlockPos pos,
    BlockState blockState,
    ResourceLocation[] unlockedSchematics
)
```

这个方法可用于得到当前目标物品、当前模块槽位、玩家解锁状态和环境下可用的方案。

处理逻辑应分两种：

- **已经选中具体方案**：只使用该方案的材料数据。
- **只选中了模块槽位，还没有选方案**：对该槽位所有可用方案的材料数据取并集。

这样才能支持“钻石镐 → 头部（右）”后直接查看所有合法材料。

### 3.4 方案允许的材料

`UpgradeSchematic` 提供：

```text
getApplicableMaterials()
getNumMaterialSlots()
acceptsMaterial(ItemStack target, String slot, int materialSlot, ItemStack material)
getRequiredQuantity(ItemStack target, int materialSlot, ItemStack material)
```

其中：

- `getApplicableMaterials()` 适合构造材料类别/材料前缀的候选集合。
- `acceptsMaterial(...)` 适合对具体物品做最终合法性校验。
- `getRequiredQuantity(...)` 只用于材料数量，不应作为材料显示过滤的唯一依据。

如果需要支持第三方 Tetra 材料，不能写死铁、钻石、下界合金等原版物品；必须读取 Tetra 当前数据注册表。

## 4. 正确的 JEI 集成方向

当前环境有独立的 JEI Tetra：

```text
JEI Tetra-6.9.0-1.2.0-fix.jar
```

该插件注册了 Tetra 的自定义材料类型：

```text
se.mickelus.tetra.module.data.MaterialData
```

并注册了 JEI 自定义材料配方类别，RecipeType UID 为：

```text
jeitetra:material
```

建议通过反射隔离可选依赖，不要在公共/common 类直接引用 Tetra 类。

推荐流程：

1. 从 JEI runtime 获取 `IIngredientManager`。
2. 通过反射获取 `MaterialData` 的 `Class`。
3. 调用：

```java
runtime.getIngredientManager().getIngredientTypeChecked(materialDataClass)
```

4. 获取该类型的全部 JEI 材料。
5. 用当前槽位可用方案的 `getApplicableMaterials()`、材料 `key/category` 过滤出候选材料。
6. 为每个候选材料创建：

```java
IFocusFactory.createFocus(
    RecipeIngredientRole.INPUT,
    materialIngredientType,
    materialData
)
```

7. 调用：

```java
runtime.getRecipesGui().show(focuses)
```

这样是让 JEI Tetra 自己显示材料配方，不是给 JEI 搜索框塞一长串文本，也不是在 RSI 自己画材料网格。

### 为什么不要拼 JEI 搜索文本

JEI 的 `setFilterText(String)` 是文本过滤器，不适合精确表达一组动态的 Tetra `MaterialData`：

- 多个材料之间的 OR 关系不稳定。
- 材料名称可能重复或多语言不同。
- 同一材料可能有自定义属性、第三方模组数据或隐藏材料。
- 文本搜索无法可靠表达 Tetra 方案的合法性。

应该使用 JEI 的 ingredient focus/recipe lookup，而不是字符串搜索。

## 5. 交互设计要求

### 推荐入口

在 Tetra 加工台界面增加一个很小的按钮，例如：

```text
JEI
```

按钮行为：

- 当前已经选中具体方案：显示该方案允许的材料。
- 只选中模块槽位：显示该槽位所有可用方案材料的并集。
- 没有目标物品或没有选中模块槽位：显示明确提示，不要发网络请求。
- JEI Tetra 不存在：按钮不崩溃，可以隐藏或提示“需要 JEI Tetra”。

不要默认自动打开 JEI，因为用户每次点击 Tetra 槽位都可能被强制切走界面；默认“一键查看”更兼容。若将来做自动打开，应增加冷却和状态变化检测，只在槽位从 A 变为 B 时触发一次。

### RS 提取功能应与 JEI 查询分离

RS 补料是另一个功能：

- JEI 查询：展示合法材料和获取方式。
- RS 补料：用户明确点击后，从 RS 取出材料放入 Tetra 槽位。

不要为了实现 JEI 查询而先扫描 RS，也不要让“RS 没有材料”影响 JEI 显示。JEI 应显示所有合法材料；RS 只负责判断哪些材料库存中存在并提供提取。

## 6. 输入和事件兼容性

之前失败实现的主要问题之一是拦截了 Tetra/JEI 的输入事件。

必须遵守：

- 不要在 `ScreenEvent.KeyPressed.Pre` 中吞掉所有按键。
- 不要手动转发字符、退格、Ctrl 组合键。
- 不要用自定义搜索框替代 JEI 的搜索框。
- 如确实需要输入框，只使用 Minecraft 原生 `EditBox`，让它成为当前 Screen 的正常 focused child。
- 不要在 `ScreenEvent.MouseButtonPressed.Pre` 中高优先级取消 Tetra 的点击事件。
- 不要在每帧自动向服务端发送查询。

如果只是打开 JEI 材料页，最好完全删除 RSI 自定义输入框。

## 7. JEI 避让要求

如果在 Tetra 界面上增加 RSI 按钮或小提示：

- 通过 JEI 的 `IGuiHandlerRegistration` 为 Tetra `WorkbenchScreen` 注册额外区域。
- 额外区域只覆盖实际增加的很小按钮。
- 不要把一个大面板注册成 JEI 避让区。
- 如果最终不绘制侧栏，只增加一个按钮，JEI 只需要避让按钮区域，甚至可以不注册额外区域。
- 不要对所有容器屏幕注册避让。

## 8. 卷轴处理原则

Tetra 卷轴不是普通一次性加工材料。

后续实现必须：

- 不把卷轴放入材料候选列表。
- 不从 RS 自动抽取卷轴作为加工材料。
- 不默认消耗卷轴。
- 方案解锁、方案选择和卷轴使用由 Tetra 原生逻辑处理。
- RSI 只处理实际的木材、金属、宝石、纤维等材料查询和可选的 RS 补入。

具体卷轴是否消耗，应以对应卷轴类型和 Tetra 原生逻辑为准，不能一概而论。

## 9. 可选依赖隔离

Tetra 和 JEI Tetra 都是可选模组。建议：

- 所有 Tetra 反射调用集中在 `mods/tetra/client` 下的少数类。
- 公共类不出现 Tetra 类型字段、参数、返回值或继承关系。
- 不在 common 初始化阶段直接加载 Tetra 类。
- 对 `ReflectiveOperationException`、`RuntimeException`、`LinkageError` 做温和降级。
- JEI Tetra 不存在时，不能因为按钮或 JEI 插件导致客户端崩溃。
- 不要为了可选模组把 Tetra 开发依赖加入主工程硬链接。

## 10. 历史验收标准（按钮流程已被当前实现取代）

### 必须通过

- 钻石镐放入 Tetra 加工台。
- 只点击“头部（右）”，不点击具体卷轴。
- 点击 JEI 入口后，JEI 显示该槽位所有可用 Tetra 材料。
- 选择某个具体方案后，JEI 结果缩小到该方案允许的材料。
- 第三方 Tetra 材料能被识别，不写死原版物品。
- JEI Tetra 的材料配方仍然正常打开。
- JEI 材料列表不覆盖 Tetra 主界面。
- 搜索框不再抢 Tetra/JEI 的键盘事件；最好完全不需要自定义搜索框。
- Tetra 卷轴不被当作消耗材料。

### 兼容性测试

至少测试：

1. Tetra + JEI + JEI Tetra 全部存在。
2. Tetra 存在但 JEI Tetra 不存在。
3. Tetra 不存在。
4. JEI 不存在但项目其他功能存在。
5. 目标物品为空。
6. 只选中槽位，未选中方案。
7. 已选中方案。
8. 方案无材料槽，例如只需要打磨/工具的方案。
9. 第三方材料有 NBT 或自定义属性。
10. JEI 当前已经打开其他页面时再次触发。

## 11. 当前实现状态

本节以下旧记录描述的是 2026-09-23 本轮实现之前的探索与清理状态；若与文档顶部实现更新冲突，以顶部为准。当前代码通过 Tetra 原生材料物品栈和合法性判断筛选 JEI，不绘制 Tetra 材料侧栏、不添加额外按钮，也不调用 JEI Tetra API。

交付前验证：

- `compileJava` 通过。
- `JeiNetworkItemCacheTest` 通过，覆盖无 NBT 空标签归一化及带 NBT 变体精确库存映射。
- `TetraJeiMixinContractTest` 通过，确认 JEI 过滤器结果替换使用可取消回调。
- 尚未在游戏内验证工作台槽位变化、搜索交集和右侧库存数量显示。
- JAR 只生成在项目 `build/libs` 下，没有替换游戏实例中的文件。
