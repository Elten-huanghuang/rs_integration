package com.huanghuang.rsintegration.mods.farmandcharm;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.farmandcharm.LetsDoCookingPotRecipeHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Let's Do 锅具的材料声明口径（需要真实 {@link ItemStack}，因此走 Bootstrap 测试基类）。
 * <p>
 * 这里守住物耗守恒的两条前提：
 * <ol>
 *   <li><b>一个材料条目恒等于一个物理槽位</b> —— 下游只按下标取材料，条目与槽位错位会串槽，
 *       批次结算的物料守恒随之失效；</li>
 *   <li><b>逐份放大</b> —— 原生每完成一份扣 1，输出 N 份却只声明 1 份材料，
 *       批次会在中途因缺料停摆；容器同理，每个成品各扣一个。</li>
 * </ol>
 */
class LetsDoCookingPotBatchDelegateTest extends com.huanghuang.rsintegration.testutil.BootstrapTest {

    private static final int INGREDIENTS_AREA = LetsDoCookingPotRecipeHandler.INGREDIENTS_AREA;

    /** 用真实 Ingredient 造 n 条食材（材质都指向同一个物品，测试只关心条目形状）。 */
    private static List<Ingredient> ingredients(int count) {
        List<Ingredient> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(Ingredient.of(Items.BOWL));
        return list;
    }

    @Test
    void eachIngredientOccupiesExactlyOneSlot() {
        List<IngredientSpec> specs = LetsDoCookingPotBatchDelegate.buildSpecs(
                ingredients(4), ItemStack.EMPTY, 1);
        assertNotNull(specs);
        assertEquals(4, specs.size(), "4 条食材应对应 4 个条目/槽位");
        for (IngredientSpec spec : specs) {
            assertEquals(1, spec.count(), "每条食材固定 1 份：一次合成每个槽位只扣 1");
        }
    }

    @Test
    void containerEntryCarriesEveryPortionConsumed() {
        // 输出 3 份 → 原生扣 3 个碗，因此容器条目必须是 3 份。
        List<IngredientSpec> specs = LetsDoCookingPotBatchDelegate.buildSpecs(
                ingredients(2), new ItemStack(Items.BOWL), 3);
        assertNotNull(specs);
        assertEquals(3, specs.size());
        assertEquals(3, specs.get(2).count());
    }

    @Test
    void recipeWithoutContainerDeclaresNoContainerEntry() {
        List<IngredientSpec> specs = LetsDoCookingPotBatchDelegate.buildSpecs(
                ingredients(3), null, 1);
        assertNotNull(specs);
        assertEquals(3, specs.size());
        assertTrue(specs.stream().allMatch(spec -> spec.count() == 1));
    }

    @Test
    void emptyIngredientEntriesAreSkipped() {
        List<Ingredient> withGaps = new ArrayList<>();
        withGaps.add(Ingredient.of(Items.BOWL));
        withGaps.add(Ingredient.EMPTY);
        withGaps.add(Ingredient.of(Items.BOWL));
        List<IngredientSpec> specs = LetsDoCookingPotBatchDelegate.buildSpecs(
                withGaps, ItemStack.EMPTY, 1);
        assertNotNull(specs);
        assertEquals(2, specs.size(), "空条目不占槽位，否则会与插料下标错位");
    }

    @Test
    void recipeWiderThanSixSlotsIsRejectedNotTruncated() {
        assertNull(LetsDoCookingPotBatchDelegate.buildSpecs(
                ingredients(INGREDIENTS_AREA + 1), ItemStack.EMPTY, 1),
                "超出槽位数的配方应整体判为不可执行，而不是悄悄截断");
        assertNotNull(LetsDoCookingPotBatchDelegate.buildSpecs(
                ingredients(INGREDIENTS_AREA), ItemStack.EMPTY, 1),
                "正好占满 6 个食材槽位是合法的");
    }

    @Test
    void slotLayoutMatchesNativeBlockEntities() {
        // 与反编译核对过的原生常量一致：0–5 食材、6 容器、7 产物。
        assertEquals(6, LetsDoCookingPotRecipeHandler.INGREDIENTS_AREA);
        assertEquals(6, LetsDoCookingPotRecipeHandler.CONTAINER_SLOT);
        assertEquals(7, LetsDoCookingPotRecipeHandler.OUTPUT_SLOT);
        assertEquals(8, LetsDoCookingPotRecipeHandler.INVENTORY_SIZE);
    }

    @Test
    void ingredientsWithCraftingRemainderBlockInputBuffer() {
        // 原生扣料是"命中槽 shrink(1) 后若有余留物则整槽替换"，
        // 预载的多份材料会被余留物顶掉，因此带余留物的食材必须判为有风险。
        assertTrue(LetsDoCookingPotBatchDelegate.ingredientLeavesRemainder(
                Ingredient.of(new ItemStack(Items.WATER_BUCKET))),
                "水桶这类带余留物的食材会顶掉预载的其余材料，必须拦下");
        assertFalse(LetsDoCookingPotBatchDelegate.ingredientLeavesRemainder(
                Ingredient.of(new ItemStack(Items.BOWL))),
                "普通食材只会 shrink(1)，可以安全预载");
    }
}
