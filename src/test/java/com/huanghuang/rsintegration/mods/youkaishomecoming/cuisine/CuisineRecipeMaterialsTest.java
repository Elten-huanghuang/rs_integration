package com.huanghuang.rsintegration.mods.youkaishomecoming.cuisine;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuisineRecipeMaterialsTest extends BootstrapTest {
    @Test
    void completedFoodBaseIsIncludedBeforeRoe() throws Exception {
        // 原版食物代替加州卷，验证“不在两个模型表中的成品基底”分支。
        List<Ingredient> materials = CuisineRecipeMaterials.collect(
                new ResourceLocation("minecraft:bread"), List.of(Ingredient.of(Items.BEETROOT_SEEDS)),
                Map.of(), Map.of());

        assertItems(materials, Items.BREAD, Items.BEETROOT_SEEDS);
    }

    @Test
    void salmonLoverRollKeepsBaseAndThreeRepeatedFishInputs() throws Exception {
        Ingredient roe = Ingredient.of(Items.BEETROOT_SEEDS);
        Ingredient salmon = Ingredient.of(Items.SALMON);
        List<Ingredient> materials = CuisineRecipeMaterials.collect(
                new ResourceLocation("minecraft:bread"), List.of(roe, salmon, salmon, salmon),
                Map.of(), Map.of());

        assertItems(materials, Items.BREAD, Items.BEETROOT_SEEDS, Items.SALMON, Items.SALMON, Items.SALMON);
        assertSame(salmon, materials.get(2));
    }

    @Test
    void rainbowRollKeepsBaseAndOrderedToppings() throws Exception {
        List<Ingredient> materials = CuisineRecipeMaterials.collect(
                new ResourceLocation("minecraft:bread"), List.of(Ingredient.of(Items.BEETROOT_SEEDS),
                        Ingredient.of(Items.SALMON), Ingredient.of(Items.COD), Ingredient.of(Items.TROPICAL_FISH)),
                Map.of(), Map.of());

        assertItems(materials, Items.BREAD, Items.BEETROOT_SEEDS, Items.SALMON, Items.COD, Items.TROPICAL_FISH);
    }

    @Test
    void variantModelStillUsesRawIngredientsAndSkipsEmptyHandTransitions() throws Exception {
        ResourceLocation base = new ResourceLocation("minecraft:bread");
        Ingredient choice = Ingredient.of(Items.COD, Items.SALMON);
        List<Ingredient> materials = CuisineRecipeMaterials.collect(base, List.of(choice),
                Map.of(base, new VariantModel()), Map.of());

        assertItems(materials, Items.WHEAT, Items.DRIED_KELP, Items.COD);
        assertSame(choice, materials.get(2));
    }

    @Test
    void fixedModelStillUsesRawIngredients() throws Exception {
        ResourceLocation base = new ResourceLocation("minecraft:bread");
        List<Ingredient> materials = CuisineRecipeMaterials.collect(base, List.of(),
                Map.of(), Map.of(base, new FixedModel()));

        assertItems(materials, Items.WHEAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"youkaishomecoming:missing_base", "youkaisfeasts:missing_base", "minecraft:air"})
    void unresolvedBaseCannotProduceAToppingsOnlyPlan(String base) throws Exception {
        assertTrue(CuisineRecipeMaterials.collect(new ResourceLocation(base),
                List.of(Ingredient.of(Items.SALMON)), Map.of(), Map.of()).isEmpty());
    }

    private static void assertItems(List<Ingredient> materials, Item... expected) {
        assertEquals(expected.length, materials.size());
        for (int index = 0; index < expected.length; index++) {
            assertTrue(materials.get(index).test(new ItemStack(expected[index])), "材料位置 " + index);
        }
    }

    public static final class VariantModel {
        public void collectIngredients(List<Ingredient> base, List<Ingredient> extra) {
            base.add(Ingredient.of(Items.WHEAT));
            base.add(Ingredient.EMPTY);
            base.add(Ingredient.of(Items.DRIED_KELP));
            extra.add(Ingredient.EMPTY);
        }
    }

    public static final class FixedModel {
        public void collectIngredients(List<Ingredient> base) {
            base.add(Ingredient.of(Items.WHEAT));
        }
    }
}
