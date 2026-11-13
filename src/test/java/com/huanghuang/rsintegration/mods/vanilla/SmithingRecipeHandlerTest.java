package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials.Origin;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials.Pool;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmithingRecipeHandlerTest extends BootstrapTest {

    @BeforeAll
    static void registerSmithingTypeForClassificationTest() {
        if (ModType.findById("smithing") == null) {
            ModType.register("smithing", new String[]{
                    "net.minecraft.world.item.crafting.SmithingTransformRecipe",
                    "net.minecraft.world.item.crafting.SmithingTrimRecipe"
            }, new String[]{"smithing_table"}, new String[]{"smithing"}, () -> null);
        }
    }

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
    void smithingSubclassUsesTheSmithingExecutionType() {
        SmithingTransformRecipe wrapped = new SmithingTransformRecipe(
                new ResourceLocation("test", "wrapped_smithing"),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                Ingredient.of(Items.DIAMOND_CHESTPLATE),
                Ingredient.of(Items.NETHERITE_INGOT),
                new ItemStack(Items.NETHERITE_CHESTPLATE)) {};

        assertEquals(ModType.byId("smithing"), ModType.classifyRecipe(wrapped));
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

    @ParameterizedTest
    @ValueSource(strings = {
            "{Damage:1325,Enchantments:[{id:\"minecraft:sharpness\",lvl:2s}]}",
            "{Damage:1026,Enchantments:[{id:\"minecraft:sharpness\",lvl:2s}],apoth.equipment:1b}"
    })
    void defaultDamageOnlyDisplayOutputAllowsThePreselectedEnchantedDamagedBase(String selectedTag) throws Exception {
        SmithingTransformRecipe recipe = new SmithingTransformRecipe(
                new ResourceLocation("test", "damage_only_upgrade"),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                Ingredient.of(Items.DIAMOND_SWORD), Ingredient.of(Items.NETHERITE_INGOT),
                new ItemStack(Items.NETHERITE_SWORD));
        ItemStack displayedOutput = new ItemStack(Items.NETHERITE_SWORD);
        displayedOutput.getOrCreateTag().putInt("Damage", 0);
        ItemStack selectedSword = new ItemStack(Items.DIAMOND_SWORD);
        selectedSword.setTag(TagParser.parseTag(selectedTag));

        List<IngredientSpec> specs = SmithingRecipeHandler.requireDemandedOutputTag(
                recipe, new SmithingRecipeHandler().getIngredients(recipe), displayedOutput);

        assertFalse(IngredientMatcher.requiresNbt(specs.get(1).ingredient()));
        assertTrue(IngredientMatcher.test(specs.get(1).ingredient(), selectedSword));

        CountedOperationMaterials materials = new CountedOperationMaterials.Builder(1, 1)
                .add(specs.get(0), new Pool().add(Origin.INITIAL,
                        new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), 1))
                .add(specs.get(1), new Pool().add(Origin.INITIAL, selectedSword, 1))
                .add(specs.get(2), new Pool().add(Origin.INITIAL, new ItemStack(Items.NETHERITE_INGOT), 1))
                .build();
        List<ItemStack> batch = materials.batchMaterials(0, 0, 1);
        ItemStack assembled = SmithingRecipeHandler.assembleTransform(recipe, batch, RegistryAccess.EMPTY);

        assertTrue(assembled.is(Items.NETHERITE_SWORD));
        assertEquals(selectedSword.getTag(), batch.get(1).getTag());
        assertEquals(selectedSword.getTag(), assembled.getTag());
        assertTrue(IngredientMatcher.matchesProducedOutput(displayedOutput, assembled));

        List<IngredientSpec> exactSelection = SmithingRecipeHandler.requireExactBase(
                recipe, specs, selectedSword);
        assertSame(exactSelection, SmithingRecipeHandler.requireDemandedOutputTag(
                recipe, exactSelection, displayedOutput));
        ItemStack otherSword = selectedSword.copy();
        otherSword.getTag().putInt("Damage", 1324);
        assertTrue(IngredientMatcher.test(exactSelection.get(1).ingredient(), selectedSword));
        assertFalse(IngredientMatcher.test(exactSelection.get(1).ingredient(), otherSword));
        assertThrows(IllegalArgumentException.class, () -> new CountedOperationMaterials.Builder(1, 1)
                .add(exactSelection.get(1), new Pool().add(Origin.INITIAL, otherSword, 1)));
    }

    @Test
    void defaultDamageDisplayPreservesTheRecipesStrictBaseRequirement() {
        ItemStack pristineBase = new ItemStack(Items.DIAMOND_SWORD);
        pristineBase.setDamageValue(0);
        ItemStack displayedOutput = new ItemStack(Items.NETHERITE_SWORD);
        displayedOutput.setDamageValue(0);
        SmithingTransformRecipe recipe = new SmithingTransformRecipe(
                new ResourceLocation("test", "strict_pristine_upgrade"),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                StrictNBTIngredient.of(pristineBase), Ingredient.of(Items.NETHERITE_INGOT), displayedOutput);
        List<IngredientSpec> original = new SmithingRecipeHandler().getIngredients(recipe);

        List<IngredientSpec> specs = SmithingRecipeHandler.requireDemandedOutputTag(
                recipe, original, displayedOutput);

        assertSame(original, specs);
        assertTrue(IngredientMatcher.requiresNbt(specs.get(1).ingredient()));
        assertTrue(IngredientMatcher.test(specs.get(1).ingredient(), pristineBase));
        ItemStack damagedBase = pristineBase.copy();
        damagedBase.setDamageValue(1);
        assertFalse(IngredientMatcher.test(specs.get(1).ingredient(), damagedBase));
        assertThrows(IllegalArgumentException.class, () -> new CountedOperationMaterials.Builder(1, 1)
                .add(specs.get(1), new Pool().add(Origin.INITIAL, damagedBase, 1)));
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
