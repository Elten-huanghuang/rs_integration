package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmithingRecipeHandlerTest extends BootstrapTest {

    @Test
    void actualBaseNbtIsRenderedAndPreservedByAssembly() {
        SmithingTransformRecipe recipe = recipe();
        ItemStack base = taggedChestplate("bound-variant");
        ItemStack result = SmithingRecipeHandler.assembleTransform(recipe, List.of(
                new ItemStack(Items.NETHERITE_INGOT),
                base,
                new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)),
                RegistryAccess.EMPTY);

        assertTrue(result.is(Items.NETHERITE_CHESTPLATE));
        assertEquals("bound-variant", result.getTag().getString("rsi_test_variant"));
    }

    @Test
    void previewSelectsTheConcreteTaggedBaseAndPinsItsNbt() {
        SmithingTransformRecipe recipe = recipe();
        ItemStack selected = taggedChestplate("selected");
        CompoundTag otherTag = new CompoundTag();
        otherTag.putString("rsi_test_variant", "other");
        Map<CraftingResolver.StackKey, Integer> available = Map.of(
                new CraftingResolver.StackKey(selected.getItem(), selected.getTag().toString()), 1,
                new CraftingResolver.StackKey(Items.DIAMOND_CHESTPLATE, otherTag.toString()), 0);

        ItemStack actual = SmithingRecipeHandler.selectAvailableBase(recipe, available, 1);
        List<IngredientSpec> exact = SmithingRecipeHandler.requireExactBase(
                recipe, new SmithingRecipeHandler().getIngredients(recipe), actual);
        ItemStack previewOutput = SmithingRecipeHandler.assembleWithBase(
                recipe, actual, RegistryAccess.EMPTY);
        ItemStack wrongVariant = taggedChestplate("wrong");

        assertEquals("selected", actual.getTag().getString("rsi_test_variant"));
        assertTrue(exact.get(1).ingredient().test(actual));
        assertFalse(exact.get(1).ingredient().test(wrongVariant));
        assertEquals("selected", previewOutput.getTag().getString("rsi_test_variant"));
    }

    @Test
    void transformOutputNbtIsDeclaredAsRuntimeDependent() {
        assertTrue(new SmithingRecipeHandler().hasRuntimeDependentPrimaryNbt(recipe()));
    }

    @Test
    void demandedOutputTagPinsTheSmithingBaseVariant() {
        SmithingTransformRecipe recipe = recipe();
        ItemStack demanded = new ItemStack(Items.NETHERITE_CHESTPLATE);
        demanded.getOrCreateTag().putString("rsi_test_variant", "selected");

        List<IngredientSpec> exact = SmithingRecipeHandler.requireDemandedOutputTag(
                recipe, new SmithingRecipeHandler().getIngredients(recipe), demanded);

        assertTrue(exact.get(1).ingredient().test(taggedChestplate("selected")));
        assertFalse(exact.get(1).ingredient().test(taggedChestplate("other")));
    }

    private static SmithingTransformRecipe recipe() {
        return new SmithingTransformRecipe(
                new ResourceLocation("test", "tagged_chestplate_upgrade"),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                Ingredient.of(Items.DIAMOND_CHESTPLATE),
                Ingredient.of(Items.NETHERITE_INGOT),
                new ItemStack(Items.NETHERITE_CHESTPLATE));
    }

    private static ItemStack taggedChestplate(String variant) {
        ItemStack stack = new ItemStack(Items.DIAMOND_CHESTPLATE);
        stack.getOrCreateTag().putString("rsi_test_variant", variant);
        return stack;
    }
}
