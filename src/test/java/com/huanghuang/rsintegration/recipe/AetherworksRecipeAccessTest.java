package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AetherworksRecipeAccessTest extends BootstrapTest {
    @Test
    void missingOptionalAdditionPreservesPrimaryMaterialAcrossRepeatedReads() {
        var handler = new AetherworksRecipeHandler();
        var recipe = new InputOnlyRecipe();
        for (int i = 0; i < 3; i++) {
            var specs = handler.getIngredients(recipe);
            assertEquals(1, specs.size());
            assertEquals(1, specs.get(0).count());
            assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.IRON_INGOT)));
        }
    }

    @Test
    void supportedAdditionRemainsARequiredMaterial() {
        var specs = new AetherworksRecipeHandler().getIngredients(new AdditionRecipe());
        assertEquals(2, specs.size());
        assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.IRON_INGOT)));
        assertEquals(1, specs.get(1).count());
        assertTrue(specs.get(1).ingredient().test(new ItemStack(Items.GOLD_INGOT)));
    }

    @Test
    void skipsAnIncompatibleOneArgumentOverload() {
        ItemStack result = AetherworksRecipeAccess.result(
                new MixedOverloads(), RegistryAccess.EMPTY, List.of("getOutput"));

        assertEquals(Items.DIAMOND, result.getItem());
    }

    @Test
    void findsAnInheritedOutputFieldWithoutRepeatedProbing() {
        ItemStack first = AetherworksRecipeAccess.result(
                new FieldRecipe(), RegistryAccess.EMPTY, List.of("missing"));
        ItemStack second = AetherworksRecipeAccess.result(
                new FieldRecipe(), RegistryAccess.EMPTY, List.of("missing"));

        assertEquals(Items.EMERALD, first.getItem());
        assertEquals(Items.EMERALD, second.getItem());
    }

    public static final class MixedOverloads {
        public ItemStack getOutput(String ignored) {
            throw new AssertionError("incompatible overload must not be invoked");
        }

        public ItemStack getOutput() {
            return new ItemStack(Items.DIAMOND);
        }
    }

    private static class FieldBase {
        @SuppressWarnings("unused")
        private final ItemStack output = new ItemStack(Items.EMERALD);
    }

    private static final class FieldRecipe extends FieldBase {}

    public static class InputOnlyRecipe extends ShapelessRecipe {
        public InputOnlyRecipe() {
            super(new ResourceLocation("rs_integration", "test_anvil"), "",
                    CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));
        }

        public Ingredient getDisplayInput() {
            return Ingredient.of(Items.IRON_INGOT);
        }
    }

    public static final class AdditionRecipe extends InputOnlyRecipe {
        public Ingredient getAddition() {
            return Ingredient.of(Items.GOLD_INGOT);
        }
    }
}
