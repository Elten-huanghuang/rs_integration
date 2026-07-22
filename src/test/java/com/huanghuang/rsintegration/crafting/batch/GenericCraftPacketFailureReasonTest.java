package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericCraftPacketFailureReasonTest extends BootstrapTest {
    @Test
    void identifiesExactNbtShortageWhenEnoughSameItemExists() {
        ItemStack expected = new ItemStack(Items.POTION);
        expected.getOrCreateTag().putString("Potion", "minecraft:water");
        Map<IngredientKey, PlanResponse.Availability> materials = Map.of(
                IngredientKey.of(expected), new PlanResponse.Availability(3, 0));
        Map<Item, Integer> available = Map.of(Items.POTION, 3);

        assertTrue(GenericCraftPacket.hasNbtMismatch(materials, available));
    }

    @Test
    void ordinaryCountShortageIsNotReportedAsNbtMismatch() {
        ItemStack expected = new ItemStack(Items.POTION);
        expected.getOrCreateTag().putString("Potion", "minecraft:water");
        Map<IngredientKey, PlanResponse.Availability> materials = Map.of(
                IngredientKey.of(expected), new PlanResponse.Availability(3, 1));

        assertFalse(GenericCraftPacket.hasNbtMismatch(materials, Map.of(Items.POTION, 2)));
    }

    @Test
    void craftingRecipeUsesSpecializedHandlerIngredients() {
        ModRecipeHandlers.register(new SpecializedCraftingHandler());
        SpecializedCraftingRecipe recipe = new SpecializedCraftingRecipe();

        List<IngredientSpec> specs = GenericCraftPacket.extractPlanIngredientSpecs(recipe);

        assertTrue(specs.stream().anyMatch(spec -> spec.ingredient().test(new ItemStack(Items.STICK))));
        assertTrue(specs.stream().anyMatch(spec -> spec.ingredient().test(new ItemStack(Items.DIAMOND))));
    }

    private static final class SpecializedCraftingRecipe extends ShapelessRecipe {
        private SpecializedCraftingRecipe() {
            super(new ResourceLocation("rs_integration", "handler_aware_test"), "",
                    CraftingBookCategory.MISC, new ItemStack(Items.EMERALD),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STICK)));
        }
    }

    private static final class SpecializedCraftingHandler implements ModRecipeHandler {
        @Override public ModType modType() { return ModType.byId("generic"); }
        @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof SpecializedCraftingRecipe; }
        @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
            return new ItemStack(Items.EMERALD);
        }
        @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
            return List.of(new IngredientSpec(Ingredient.of(Items.STICK), 1),
                    new IngredientSpec(Ingredient.of(Items.DIAMOND), 1));
        }
    }
}
