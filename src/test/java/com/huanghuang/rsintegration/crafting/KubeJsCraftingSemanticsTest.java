package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubeJsCraftingSemanticsTest extends BootstrapTest {

    @Test
    void keepActionRequiresOneCatalystForLargeBatch() {
        ShapelessRecipe recipe = recipe("keep", Ingredient.of(Items.BLAZE_ROD));

        IngredientSpec spec = apply(recipe, new FakeAction("keep", -1, Items.BLAZE_ROD)).get(0);

        assertEquals(DemandRole.CATALYST, spec.role());
        assertEquals(1, CraftPacketUtils.requiredCount(spec, 64));
    }

    @Test
    void realPyrotheumDustRecipeKeepsOneFocusAcross64Executions() {
        // kubejs:kjs/thaumjourney_pyrotheum_dust from thaumcraft.js:
        // focus_fire + gunpowder + coal + blaze_powder, with focus_fire kept.
        ShapelessRecipe recipe = recipe("thaumjourney_pyrotheum_dust",
                Ingredient.of(Items.BLAZE_ROD), Ingredient.of(Items.GUNPOWDER),
                Ingredient.of(Items.COAL), Ingredient.of(Items.BLAZE_POWDER));

        List<IngredientSpec> specs = apply(recipe,
                new FakeAction("keep", -1, Items.BLAZE_ROD));

        assertEquals(List.of(1, 64, 64, 64), specs.stream()
                .map(spec -> CraftPacketUtils.requiredCount(spec, 64)).toList());
        assertEquals(DemandRole.CATALYST, specs.get(0).role());
    }

    @Test
    void realLightningBottleRecipeKeepsTeslaBulbAcross64Executions() {
        // alexs_cave.js: tesla_bulb + glass_bottle -> lightning_bottle.
        ShapelessRecipe recipe = recipe("lightning_bottle",
                Ingredient.of(Items.REDSTONE_TORCH), Ingredient.of(Items.GLASS_BOTTLE));

        List<IngredientSpec> specs = apply(recipe,
                new FakeAction("keep", -1, Items.REDSTONE_TORCH));

        assertEquals(1, CraftPacketUtils.requiredCount(specs.get(0), 64));
        assertEquals(64, CraftPacketUtils.requiredCount(specs.get(1), 64));
    }

    @Test
    void realTransformTagRecipeKeepsOneOfTwoReusableCandidates() {
        // thaumcraft.js uses #thaumcraft:transform in the centre slot. The tag
        // contains primordial_pearl, salis_mundus and material_impetus_cell;
        // only the first and third have keepIngredient actions.
        Ingredient transform = Ingredient.of(
                Items.ENDER_PEARL, Items.GLOWSTONE_DUST, Items.ENDER_EYE);
        NonNullList<Ingredient> ingredients = NonNullList.withSize(9, Ingredient.of(Items.PAPER));
        ingredients.set(4, transform);
        ShapedRecipe recipe = new ShapedRecipe(new ResourceLocation("test", "transform_tag"), "",
                CraftingBookCategory.MISC, 3, 3, ingredients,
                new ItemStack(Items.KNOWLEDGE_BOOK));

        List<IngredientSpec> specs = KubeJsCraftingSemantics.applyIngredientActions(recipe, List.of(
                new FakeAction("keep", -1, Items.ENDER_PEARL),
                new FakeAction("keep", -1, Items.ENDER_EYE)));
        IngredientSpec transformSpec = specs.get(4);

        assertEquals(DemandRole.CATALYST, transformSpec.role());
        assertEquals(1, CraftPacketUtils.requiredCount(transformSpec, 64));
        assertTrue(transformSpec.ingredient().test(new ItemStack(Items.ENDER_PEARL)));
        assertTrue(transformSpec.ingredient().test(new ItemStack(Items.ENDER_EYE)));
        assertFalse(transformSpec.ingredient().test(new ItemStack(Items.GLOWSTONE_DUST)));
    }

    @Test
    void filteredKeepNarrowsMixedIngredientToReusableCandidates() {
        Ingredient mixed = Ingredient.of(Items.BLAZE_ROD, Items.BLAZE_POWDER);
        ShapelessRecipe recipe = recipe("mixed_keep", mixed);

        IngredientSpec spec = apply(recipe, new FakeAction("keep", -1, Items.BLAZE_ROD)).get(0);

        assertEquals(DemandRole.CATALYST, spec.role());
        assertTrue(spec.ingredient().test(new ItemStack(Items.BLAZE_ROD)));
        assertFalse(spec.ingredient().test(new ItemStack(Items.BLAZE_POWDER)));
    }

    @Test
    void consumeActionOverridesNativeContainerRemainder() {
        ShapelessRecipe recipe = recipe("consume", Ingredient.of(Items.WATER_BUCKET));

        IngredientSpec spec = apply(recipe, new FakeAction("consume", -1, Items.WATER_BUCKET)).get(0);

        assertEquals(DemandRole.CONSUMED, spec.role());
        assertEquals(16, CraftPacketUtils.requiredCount(spec, 16));
    }

    @Test
    void replaceAndDamageActionsScaleConservatively() {
        IngredientSpec replaced = apply(recipe("replace", Ingredient.of(Items.POTION)),
                new FakeAction("replace", -1, Items.POTION)).get(0);
        IngredientSpec damaged = apply(recipe("damage", Ingredient.of(Items.IRON_PICKAXE)),
                new FakeAction("damage", -1, Items.IRON_PICKAXE)).get(0);

        assertEquals(DemandRole.CONTAINER_RETURNING, replaced.role());
        assertEquals(DemandRole.TRANSFORMED, damaged.role());
        assertEquals(32, CraftPacketUtils.requiredCount(replaced, 32));
        assertEquals(32, CraftPacketUtils.requiredCount(damaged, 32));
    }

    @Test
    void customActionUsesRecipeRemainderBehavior() {
        ShapelessRecipe recipe = new ShapelessRecipe(new ResourceLocation("test", "custom"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.COPPER_INGOT),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.BLAZE_ROD))) {
            @Override
            public NonNullList<ItemStack> getRemainingItems(CraftingContainer container) {
                NonNullList<ItemStack> result = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
                result.set(0, container.getItem(0).copyWithCount(1));
                return result;
            }
        };

        IngredientSpec spec = apply(recipe, new FakeAction("custom", -1, Items.BLAZE_ROD)).get(0);

        assertEquals(DemandRole.CATALYST, spec.role());
    }

    private static List<IngredientSpec> apply(ShapelessRecipe recipe, FakeAction action) {
        return KubeJsCraftingSemantics.applyIngredientActions(recipe, List.of(action));
    }

    private static ShapelessRecipe recipe(String path, Ingredient ingredient) {
        return recipe(path, new Ingredient[]{ingredient});
    }

    private static ShapelessRecipe recipe(String path, Ingredient... ingredients) {
        return new ShapelessRecipe(new ResourceLocation("test", path), "",
                CraftingBookCategory.MISC, new ItemStack(Items.COPPER_INGOT),
                NonNullList.of(Ingredient.EMPTY, ingredients));
    }

    public static final class FakeAction {
        private final String type;
        private final int index;
        private final Item item;

        private FakeAction(String type, int index, Item item) {
            this.type = type;
            this.index = index;
            this.item = item;
        }

        public String getType() {
            return type;
        }

        public boolean checkFilter(int gridIndex, ItemStack candidate) {
            return (index < 0 || gridIndex == index) && candidate.is(item);
        }
    }
}
