package com.huanghuang.rsintegration.mods.farmandcharm;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.mods.common.InventoryListItemHandler;
import com.huanghuang.rsintegration.mods.farmersdelight.CookingPotBatchDelegate;
import com.huanghuang.rsintegration.mods.farmandcharm.LetsDoCookingPotRecipeHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Let's Do「大汤锅」批量委托基类：farm_and_charm 的烹饪锅/烘焙机与 candlelight 的大汤锅。
 * <p>
 * 三者共用同一套槽位布局（0–5 食材、6 盛装容器、7 产物）和同一份烹饪实现（反编译核对）：
 * <ul>
 *   <li>产出：7 号槽为空则写入产物，非空则只把数量加到上限；</li>
 *   <li>扣料：对每条配方食材，只从**第一个**命中的槽位扣 1，因此一份成品占用一个槽位；
 *       若该食材有合成余留物，余留物会**整槽替换**那个槽位（不是写回余数，详见
 *       {@link #hasIngredientRemainderRisk}）；</li>
 *   <li>扣容器：只要 6 号槽非空就无条件扣 1，且每个成品各扣一个（输出 N 份就扣 N 个）；</li>
 *   <li>热源：{@code isBeingBurned()} 只查 {@code farm_and_charm:allows_cooking} 标签，
 *       candlelight 的炉灶通过 {@code #c:allows_cooking} 并入该标签，两个模组共用同一条判定。</li>
 * </ul>
 * <p>
 * 与原版农夫乐事烹饪锅的差异都在这里补齐，而不是整锅照搬：
 * <ul>
 *   <li>槽位布局：容器槽变成 6、产物槽变成 7，没有原版"碗食留在展示槽"的兜底；</li>
 *   <li>库存入口：这些锅没有注册 {@code IItemHandler} 能力，改为包装原生 {@code getItems()}；</li>
 *   <li>容器口径：数量 = 每份用量 × 份数（原版假设一份只扣一个），因此
 *       {@link #getRequiredContainer} 与 {@link #getRequiredMaterials} 都按该口径重建；</li>
 *   <li>配方与机器判定：父类认的是农夫乐事的类和配方，父类的机器判定直接沿用会让预载恒关，
 *       因此两者都换成这套锅自己的口径；</li>
 *   <li>余留物风险：原版的 {@code INGREDIENT_REMAINDER_OVERRIDES} 静态表专属于原版锅的
 *       按槽位遍历扣料，这套锅只在命中槽被子内替换，因此改成"食材本身是否带余留物"。</li>
 * </ul>
 * <p>
 * 物耗守恒前提：一台机器同时只跑一个批次（由物理操作租约与机器槽位能力门控保证），
 * 一个材料条目恒等于一个槽位、每完成一份扣 1，批次结束时的实际消耗与预留相等。
 * <p>
 * 原版烹饪锅的奥术炉灶分支在这里不会命中：{@code validateAndInit} 恒把
 * {@code arcaneCookingPot} 置为 false（只有奥术锅 + 奥术炉灶的组合才会为真），
 * 因此输入预载不会被那条分支关闭。
 */
public abstract class LetsDoCookingPotBatchDelegate extends CookingPotBatchDelegate {

    // 与 CookingPotBlockEntity / RoasterBlockEntity / LargeCookingPotBlockEntity 一致，
    // 常量集中在配方 handler 上，委托与测试共用同一份定义。
    static final int INGREDIENTS_AREA = LetsDoCookingPotRecipeHandler.INGREDIENTS_AREA;
    static final int CONTAINER_SLOT = LetsDoCookingPotRecipeHandler.CONTAINER_SLOT;
    static final int OUTPUT_SLOT = LetsDoCookingPotRecipeHandler.OUTPUT_SLOT;

    /** 两个模组共用的热源标签；candlelight 的炉灶通过 {@code #c:allows_cooking} 并入其中。 */
    private static final ResourceLocation ALLOWS_COOKING_TAG =
            new ResourceLocation("farm_and_charm", "allows_cooking");

    /** 每条配方食材在计划里固定按 1 份计：一次合成每个槽位只扣 1。 */
    private static final int PER_OPERATION_INGREDIENT = 1;

    @Override
    protected boolean startOperationImpl(OperationStartContext context) {
        if (context.buffered() && startBufferedOperation(
                context.player(), context.inputBufferPlan(), context.ledger())) {
            return true;
        }
        List<ItemStack> materials = context.legacyMaterials();
        if (materials.isEmpty()) return false;
        if (context.buffered()) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-LetsDo] 输入缓冲启动未成功，退回预扣材料铺料 recipe={} delegate={}",
                    recipe == null ? null : recipe.getId(), getClass().getSimpleName());
        }
        return startPreparedOperation(context.player(), materials, context.ledger());
    }

    /** 主产物恒在 7 号槽；这套锅没有"碗食留在展示槽"的兜底。 */
    @Override
    protected int mealDisplaySlot() { return OUTPUT_SLOT; }

    @Override
    protected int inputSlots() { return INGREDIENTS_AREA; }
    @Override
    protected int containerSlot() { return CONTAINER_SLOT; }
    @Override
    protected int outputSlot() { return OUTPUT_SLOT; }
    @Override
    protected int inventorySize() { return OUTPUT_SLOT + 1; }

    @Override
    protected boolean inputBufferEnabled() {
        return RSIntegrationConfig.ENABLE_LETSDO_COOKING_POT_INPUT_BUFFER.get();
    }

    /**
     * 输入预载的机器判定必须换成这套锅的方块实体。
     * <p>
     * 父类认的是农夫乐事烹饪锅的类，直接沿用会让所有 Let's Do 锅具的
     * {@code supportsInputBuffer()} 恒为 false，整批一次发配就无从谈起。
     */
    @Override
    protected boolean supportsBufferedMachine(BlockEntity machine) {
        return isSupportedBlockEntity(machine);
    }

    /**
     * 配方判定按类名比较。
     * <p>
     * 父类认的是农夫乐事配方类，且刻意不复刻原版方法以避免 SRG 重映射问题；
     * 这里换成字符串比较，既不硬链接可选依赖，也不会误收农夫乐事配方。
     */
    @Override
    protected boolean isSupportedRecipe(Recipe<?> candidate) {
        return candidate != null
                && LetsDoCookingPotRecipeHandler.RECIPE_CLASS.equals(candidate.getClass().getName());
    }

    @Override
    protected int inputBufferLimit() {
        return Math.max(1, RSIntegrationConfig.LETSDO_COOKING_POT_INPUT_BUFFER_LIMIT.get());
    }

    /**
     * 容器口径由 {@link #getRequiredContainer} 独占，不再依赖父类的
     * "声明容器 == 产物余留"假设：这套锅的容器是配方字段，且配方不产生容器余留
     * （{@code getCraftingRemainingItem()} 为空），父类的比对必然失败而误关输入预载。
     */
    @Override
    protected boolean supportsBufferedContainer(ItemStack required, ItemStack declared) {
        return true;
    }

    /**
     * 余留物风险：这套锅的扣料实现是"命中槽位 shrink(1) 后若有合成余留物，就用余留物
     * <b>整槽替换</b>"，而不是写回余数。
     * <p>
     * 因此只要某条食材带有合成余留物，预载进同一个槽位的 N 份材料就会在第一次合成后
     * 被余留物顶掉，剩下的 N-1 份直接消失——这是物品损失，必须拦下，退回"一次一份"的
     * 单次执行路径。没有余留物的食材只会 shrink(1)，可以安全预载。
     */
    @Override
    protected boolean hasIngredientRemainderRisk(BlockEntity machine, IngredientSpec spec) {
        if (spec == null || spec.ingredient() == null) return true;
        return ingredientLeavesRemainder(spec.ingredient());
    }

    /** 食材候选中只要有任意一个带合成余留物，就判为有风险（保守放行）。 */
    static boolean ingredientLeavesRemainder(Ingredient ingredient) {
        ItemStack[] candidates = ingredient.getItems();
        if (candidates.length == 0) return true;
        for (ItemStack candidate : candidates) {
            if (candidate == null || candidate.isEmpty() || candidate.hasCraftingRemainingItem()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> ingredientRemainderOverridesClass(BlockEntity machine) {
        // 这套锅没有原版的余留物覆盖表；风险判定完全由上面的余留物检查承担。
        return null;
    }

    @Override
    protected IItemHandler getInventory(BlockEntity be) {
        if (be == null) return null;
        // 这两个模组只实现了 doapi 的 ImplementedInventory，没有注册 IItemHandler 能力，
        // getItems() 是唯一的访问入口。按方法名反射，避免硬链接到可选依赖。
        Method getItems = findGetItems(be.getClass());
        if (getItems == null) return null;
        return InventoryListItemHandler.of(() -> readItems(getItems, be));
    }

    @Nullable
    private static Method findGetItems(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getMethod("getItems");
            } catch (NoSuchMethodException ignored) {
                // 继续沿父类向上找
            }
        }
        return null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static NonNullList<ItemStack> readItems(Method getItems, BlockEntity be) {
        try {
            Object value = getItems.invoke(be);
            return value instanceof NonNullList<?> list ? (NonNullList<ItemStack>) list : null;
        } catch (ReflectiveOperationException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-LetsDo] 无法读取锅具库存", e);
            return null;
        }
    }

    /**
     * 热源判定：直接查原生标签，不再反射不存在的 {@code isHeated()}。
     * <p>
     * 只匹配 {@code farm_and_charm:allows_cooking}：它已并入 {@code #minecraft:campfires}
     * 与 candlelight 的 {@code #candlelight:stoves}，不会撞上 {@code minecraft:climbable}
     * 这类同字面量的原版标签。
     */
    @Override
    protected boolean isHeated(BlockEntity be) {
        if (be == null || be.getLevel() == null) return false;
        TagKey<Block> tag = BlockTags.create(ALLOWS_COOKING_TAG);
        BlockPos below = be.getBlockPos().below();
        return be.getLevel().getBlockState(below).is(tag);
    }

    /**
     * 单份合成要预留的容器：原型数量 1。
     * <p>
     * 父类按此数量检查"计划材料是否与预留一致"，批量时的总量由
     * {@code count × plannedOperations} 现算，因此这里必须返回单份口径。
     */
    @Override
    protected ItemStack getRequiredContainer(Recipe<?> recipe, @Nullable RegistryAccess access) {
        return LetsDoCookingPotRecipeHandler.containerForCraft(recipe);
    }

    /**
     * 每次开始的材料快照：每条配方食材各占一个槽位、固定 1 份；末尾是盛装容器，
     * 份数按"每份用量 × 份数"在预留阶段放大（原生每个成品各扣一个容器。
     * 例如输出 3 份就扣 3 个碗，若只声明 1 个会在批次中途因缺容器而停摆）。
     * <p>
     * 这里绕过父类的余留物回退分支，是为了让材料条目与槽位严格一一对应：
     * 下游只按下标取材料，条目数一旦与槽位数错位，插料与回滚就会串槽。
     */
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        return buildSpecs(recipe.getIngredients(),
                LetsDoCookingPotRecipeHandler.declaredContainer(recipe),
                LetsDoCookingPotRecipeHandler.containerPerCraft(recipe));
    }

    /**
     * 从配方条目重建材料列表：跳过空条目、每条食材恰好占一个槽位（固定 1 份），
     * 末尾追加盛装容器（份数为每份用量）。
     * <p>
     * 食材数超过槽位数时整体判为不可执行（返回 null），而不是截断——
     * 截断会让计划声称的份数与机器实际能跑的份数不符。
     *
     * @param containerPerCraft 每个成品消耗的容器数量；&lt;= 0 或容器为空时不追加容器条目
     * @return 材料列表，配方不可执行时为 null
     */
    @Nullable
    static List<IngredientSpec> buildSpecs(List<Ingredient> ingredients,
                                           @Nullable ItemStack container,
                                           int containerPerCraft) {
        List<IngredientSpec> specs = new ArrayList<>();
        int used = 0;
        if (ingredients != null) {
            for (Ingredient ingredient : ingredients) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                if (used >= INGREDIENTS_AREA) return null;
                specs.add(new IngredientSpec(ingredient, PER_OPERATION_INGREDIENT));
                used++;
            }
        }
        if (containerPerCraft > 0 && container != null && !container.isEmpty()) {
            // 容器按"每份用量"计数：原生每个成品各扣一个，声明少了会在批次中途停摆。
            specs.add(new IngredientSpec(Ingredient.of(container), containerPerCraft));
        }
        return specs;
    }
}
