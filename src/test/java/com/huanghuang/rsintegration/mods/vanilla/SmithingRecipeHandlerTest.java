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
    void actualAssemblyPreservesRepairCostAndModifierInsteadOfRebuildingABareTool() throws Exception {
        ItemStack base = new ItemStack(Items.WOODEN_SWORD);
        base.setTag(net.minecraft.nbt.TagParser.parseTag(
                "{Unbreakable:1b,RepairCost:8,Damage:0,itemModifier:\"celestial_forge:vicious\"}"));
        CompoundTag original = base.getTag().copy();
        for (var item : List.of(Items.STONE_SWORD, Items.IRON_SWORD,
                Items.DIAMOND_SWORD, Items.NETHERITE_SWORD)) {
            var recipe = new SmithingTransformRecipe(new ResourceLocation("test:modified_upgrade"),
                    Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                    Ingredient.of(base.getItem()), Ingredient.of(Items.NETHERITE_INGOT), new ItemStack(item));
            base = SmithingRecipeHandler.assembleWithBase(recipe, base, RegistryAccess.EMPTY);
            assertEquals(original, base.getTag());
        }
    }

    @Test
    void eachActualVanillaAssemblyCopiesTheUnbreakableBaseState() {
        var upgrades = List.of(Items.STONE_SWORD, Items.IRON_SWORD,
                Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);
        ItemStack base = new ItemStack(Items.WOODEN_SWORD);
        base.getOrCreateTag().putBoolean("Unbreakable", true);
        base.setDamageValue(0);
        for (var outputItem : upgrades) {
            var transform = new SmithingTransformRecipe(
                    new ResourceLocation("test", "upgrade_" + upgrades.indexOf(outputItem)),
                    Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                    Ingredient.of(base.getItem()), Ingredient.of(Items.NETHERITE_INGOT),
                    new ItemStack(outputItem));
            ItemStack demanded = new ItemStack(outputItem);
            demanded.getOrCreateTag().putInt("Unbreakable", 1);
            var specs = requireDemanded(transform, demanded);
            assertTrue(com.huanghuang.rsintegration.crafting.IngredientMatcher.test(
                    specs.get(1).ingredient(), base));
            assertFalse(com.huanghuang.rsintegration.crafting.IngredientMatcher.test(
                    specs.get(1).ingredient(), new ItemStack(base.getItem())));
            base = SmithingRecipeHandler.assembleTransform(transform, List.of(
                    new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), base,
                    new ItemStack(Items.NETHERITE_INGOT)), RegistryAccess.EMPTY);
            assertTrue(base.getTag().getBoolean("Unbreakable"));
            assertEquals(0, base.getDamageValue());
        }
    }

    private static List<IngredientSpec> requireDemanded(SmithingTransformRecipe recipe, ItemStack output) {
        return SmithingRecipeHandler.requireDemandedOutputTag(recipe,
                new SmithingRecipeHandler().getIngredients(recipe), output);
    }

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
