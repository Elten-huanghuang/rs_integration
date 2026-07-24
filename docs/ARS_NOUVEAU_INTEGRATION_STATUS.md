# Ars Nouveau 集成状态

## 当前进度：Phase 1A 完成 ✅ + i18n 完成 ✅

**提交**: 
- ef252a8 "Ars Nouveau 集成 Phase 1A：Imbuement Chamber 与 Enchanting Apparatus 基础实现"
- 488487a "添加 Ars Nouveau 集成状态文档"
- ff64233 "修复 Ars Nouveau 批处理委托并添加 i18n 翻译"

### 已实现的核心功能

#### 1. 反射探针与访问层
- ✅ `ArsNouveauReflection` - 反射合约注册（4个核心类）
- ✅ `ArsTileAccess` - 统一访问 Ars 机器内部状态
  - `isCrafting`/`counter` (Apparatus)
  - `craftTicks`/`stack` (Imbuement)
  - `getSource()`/`getMaxSource()`
  - `pedestalList()` / `getNearbyPedestals()`
- ✅ `ArsRecipeClassifier` - 配方类型精确分类（避免 instanceof 陷阱）

#### 2. 工具类
- ✅ `ArsPedestalLayout` - 基座布局捕获与稳定保持
- ✅ `ArsSourceProbe` - Source 资源快照（不作为物品材料）

#### 3. 批处理委托（IBatchDelegate 实现）
- ✅ `ArsImbuementBatchDelegate` - Imbuement Chamber 生命周期
  - 支持无基座配方（纯单槽）
  - 支持基座变体（半径 1，元素精华等）
  - 完成检测：slot0 匹配 `recipe.getResult(tile)` 且 craftTicks ≤ 0
  - 超时：100 tick 基础 + 200 tick Source 累积余量
- ✅ `ArsApparatusBatchDelegate` - Enchanting Apparatus 生命周期
  - 基座布局（半径 3）
  - 触发：`attemptCraft(catalyst, null)` 反射调用
  - 完成检测：`!isCrafting && counter==0 && slot0!=empty`
  - 避免陷阱：isCrafting 期间 `getItem(0)` 返回空栈

#### 4. 配方与模块集成
- ✅ `ArsNouveauRecipeHandler` - 产物与材料解析
  - Imbuement: 读 `output` 字段（`getResultItem()` 返回空）
  - Apparatus: 读 `result` 字段
  - 基座材料与中央输入分离提取
- ✅ `ArsNouveauRSModule` - 模块注册
  - 两个 ModType: `ars_nouveau_imbuement` / `ars_nouveau_apparatus`
  - JEI 集成占位
  - 绑定目标：`ImbuementTile` / `EnchantingApparatusTile`

#### 5. 配置与网络
- ✅ `RSIntegrationConfig.ENABLE_ARS_NOUVEAU`
- ✅ 使用 `GenericCraftPacket` 系统（无需自定义网络包）
- ✅ 模块已注册到 `RSIntegrationMod.MODULES`

### 技术亮点

✅ **配方分类防御**：用 `RecipeType` 注册 ID 匹配，不用 `instanceof`  
   → 排除 `EnchantmentRecipe`/`ArmorUpgradeRecipe`/`SpellWriteRecipe`/`ReactiveEnchantmentRecipe` 四个 NBT 变换子类型

✅ **完成检测机制**：  
   - Imbuement: 对比物品类型（不依赖"槽非空"）
   - Apparatus: 观测反射字段（绕过 isCrafting 时 getItem 返回空的陷阱）

✅ **Source 软节流**：记录成本但不作为材料需求，缺 Source 返回 WORKING + 提示文案（不阻塞启动）

✅ **基座布局稳定性**：validation 时捕获，整个批次期间保持不变

✅ **并发能力声明**：  
   - `ReservationModel.CHAIN_RESERVED`
   - `AnchorModel.MACHINE_SLOT`
   - `supportOffsets` 包含所有基座偏移

---

## 待完成项（Phase 1B/2）

### 高优先级（核心功能）

✅ **i18n 翻译键** - 已完成
- `en_us.json` / `zh_cn.json`:
  - ✅ `gui.rs_integration.jei.ars_nouveau_imbuement_craft` = "Remote Craft via Imbuement Chamber"
  - ✅ `gui.rs_integration.jei.ars_nouveau_apparatus_craft` = "Remote Craft via Enchanting Apparatus"
  - ✅ `rsi.ars_nouveau.waiting.accumulating_source` = "Accumulating Source: %s/%s"
  - ✅ `rsi.ars_nouveau.error.machine_busy` = "The machine is already crafting"
  - ✅ `rsi.ars_nouveau.error.no_pedestals` = "No pedestals found around the machine"
  - ✅ `rsi.ars_nouveau.error.recipe_not_active` = "Recipe did not activate after material placement"
  - ✅ `rsi.ars_nouveau.error.input_stolen` = "Input was removed from the machine"
  - ✅ 中文翻译：浸润仪/附魔装置/积累魔源等

⏳ **游戏内验收测试**
- [ ] Imbuement 单次合成（无基座配方，如 Amethyst）
- [ ] Imbuement 基座变体（元素精华，需 3 个基座）
- [ ] Apparatus 单次合成（普通 `enchanting_apparatus` 配方）
- [ ] 批量合成（10次 × 同配方）
- [ ] 递归合成（配方树中包含 Ars 中间件）
- [ ] Source 不足场景（验证软节流，不报错，最终完成）
- [ ] 容器剩余物（Apparatus 配方若有剩余物，验证 `getCraftingRemainingItem()` 被正确回收）
- [ ] 中止与守恒（合成中途取消，验证材料退款）
- [ ] 外部干扰（Imbuement 的 slot 被漏斗抽走，验证判定为"输入丢失"而非"产物"）

⏳ **容器剩余物收集**
- Apparatus 的 `collectPedestalRemainders()` 当前只是清空
- 需要调用 `getCraftingRemainingItem()` 并收集到结果中

### 中优先级（体验优化）

⏳ **Source 不足提示文案优化**
- 当前返回 `"Accumulating Source: X/Y"`
- 可增加限频逻辑（避免每 tick 刷屏）

⏳ **JEI 集成验证**
- `ModType.configureJei()` 已注册，需验证 JEI 配方查看器正确显示"通过 RS 合成"按钮

### 低优先级（扩展功能）

⏳ **Phase 2: Scribes Table (glyph 配方)**
- 需先反编译验证 `ScribesTile` 生命周期
- 确认输入来源、完成检测、中止可逆性
- 仅在以上确认后再接入

❌ **其余 13 种配方类型** - 默认排除
- 参考 `ARS_NOUVEAU_RECURSIVE_CRAFTING_PLAN.md` §3 表格
- `enchantment`/`crush`/`summon_ritual` 等都有技术障碍（NBT 变换/随机/实体）

---

## 参考文档

- **计划文档**: `docs/ARS_NOUVEAU_RECURSIVE_CRAFTING_PLAN.md`
- **反编译版本**: Ars Nouveau 4.12.6 (Minecraft 1.20.1, Forge 47.x)
- **测试可达面**: `docs/reference_unit_test_setup.md` （RS API 是 compileOnly，物理守恒测试需游戏内）

---

## 快速开始测试

1. **构建 mod**:
   ```bash
   ./gradlew build
   ```

2. **安装到测试环境**:
   - 复制 `build/libs/rs_integration-xxx.jar` 到 mods 目录
   - 确保已安装 Ars Nouveau 4.12.6 + Refined Storage

3. **游戏内测试步骤**:
   ```
   a. 绑定 Imbuement Chamber 到 RS 网络
   b. 在 RS 终端中选择一个 imbuement 配方（如 Amethyst Golem Charm）
   c. 点击"通过绑定机器合成"
   d. 观察合成进度与结果
   ```

4. **验证清单**:
   - [ ] 配方能被识别（不报"recipe not found"）
   - [ ] 材料正确扣除
   - [ ] 基座（如需要）被正确填充
   - [ ] Source 消耗正常
   - [ ] 产物正确返回 RS 网络
   - [ ] 中止时材料退款正确

---

*最后更新: 2026-07-24*
