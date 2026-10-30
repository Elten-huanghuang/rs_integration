package com.huanghuang.rsintegration.mods.farmersdelight;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 概率切板产物不能被当成必需次产物，否则没滚出来就判输出缺失。 */
class CuttingBoardProbabilisticOutputsTest extends BootstrapTest {
    private static CuttingBoardRecipe recipe(ChanceResult... results) {
        return new CuttingBoardRecipe(
                new ResourceLocation("farmersdelight", "cutting/probabilistic_test"),
                "",
                Ingredient.of(Items.MELON_SLICE),
                Ingredient.of(Items.IRON_SWORD),
                NonNullList.of(ChanceResult.EMPTY, results),
                "");
    }

    @Test
    void fullyProbabilisticResultsDeclareNoRequiredSecondaryOutputs() {
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(new ItemStack(Items.MELON_SLICE), 0.35F),
                new ChanceResult(new ItemStack(Items.PUMPKIN_PIE), 0.35F),
                new ChanceResult(new ItemStack(Items.COOKIE), 0.35F));

        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        List<ItemStack> secondaries = handler.getSecondaryOutputs(recipe, null);
        assertTrue(secondaries.isEmpty(), () -> "概率产物不应成为必需次产物: " + secondaries);
        assertTrue(handler.requiresTargetedProduction(recipe, new ItemStack(Items.MELON_SLICE)));
    }

    @Test
    void deterministicSecondaryOutputsStayRequired() {
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(new ItemStack(Items.MELON_SLICE), 1.0F),
                new ChanceResult(new ItemStack(Items.BOWL), 1.0F));

        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        List<ItemStack> secondaries = handler.getSecondaryOutputs(recipe, null);
        assertEquals(1, secondaries.size());
        assertEquals(Items.BOWL, secondaries.get(0).getItem());
        assertFalse(handler.requiresTargetedProduction(recipe, new ItemStack(Items.BOWL)));
    }

    @Test
    void probabilisticByproductIsDroppedWhileDeterministicPrimaryStays() {
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(new ItemStack(Items.MELON_SLICE), 1.0F),
                new ChanceResult(new ItemStack(Items.PUMPKIN_PIE), 0.35F),
                new ChanceResult(new ItemStack(Items.BOWL), 1.0F));

        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        List<ItemStack> secondaries = handler.getSecondaryOutputs(recipe, null);
        assertEquals(1, secondaries.size());
        assertEquals(Items.BOWL, secondaries.get(0).getItem());
        // 主产物确定时不走按目标重试，避免无谓地反复消耗材料。
        assertFalse(handler.requiresTargetedProduction(recipe, new ItemStack(Items.BOWL)));
    }

    @Test
    void deterministicCuttingRecipeCanBeAnIntermediateStep() {
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(new ItemStack(Items.MELON_SLICE), 1.0F),
                new ChanceResult(new ItemStack(Items.BOWL), 1.0F));

        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        assertTrue(handler.hasDeterministicPrimaryOutput(recipe));
        assertFalse(handler.hasRandomizedOutputs(recipe));
        assertFalse(handler.supportsTargetedProduction(recipe));
        assertTrue(handler.supportsDeterministicProjection(recipe));
    }

    @Test
    void guaranteedPrimaryWithRandomByproductCanBeAnIntermediateStep() {
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(new ItemStack(Items.MELON_SLICE), 1.0F),
                new ChanceResult(new ItemStack(Items.PUMPKIN_PIE), 0.35F));

        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        // 递归图只承诺必出产物，概率副产物不应阻止主产物递归。
        assertTrue(handler.hasRandomizedOutputs(recipe));
        assertTrue(handler.supportsDeterministicProjection(recipe));
        assertTrue(handler.supportsBackgroundPlanning(recipe));
        assertTrue(handler.getSecondaryOutputs(recipe, RegistryAccess.EMPTY).isEmpty());
    }

    @Test
    void probabilisticPrimaryRequiresTargetedRecursionInsteadOfDeterministicProjection() {
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(new ItemStack(Items.MELON_SLICE, 4), 0.35F),
                new ChanceResult(new ItemStack(Items.BOWL), 1.0F));

        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        assertFalse(handler.hasDeterministicPrimaryOutput(recipe));
        assertFalse(handler.supportsDeterministicProjection(recipe));
        assertFalse(handler.supportsBackgroundPlanning(recipe));
        assertTrue(handler.requiresTargetedProduction(recipe, new ItemStack(Items.MELON_SLICE)));
        assertFalse(handler.requiresTargetedProduction(recipe, new ItemStack(Items.BOWL)));
    }

    @Test
    void targetingRandomByproductDependsOnThatOutputRatherThanPrimary() {
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(new ItemStack(Items.MELON_SLICE), 1.0F),
                new ChanceResult(new ItemStack(Items.PUMPKIN_PIE), 0.35F));

        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        assertFalse(handler.requiresTargetedProduction(recipe, new ItemStack(Items.MELON_SLICE)));
        assertTrue(handler.requiresTargetedProduction(recipe, new ItemStack(Items.PUMPKIN_PIE)));
        assertFalse(handler.requiresTargetedProduction(recipe, new ItemStack(Items.DIAMOND)));
    }

    @Test
    void randomExtraOfSameItemDoesNotEraseGuaranteedSecondary() {
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(new ItemStack(Items.MELON_SLICE), 1.0F),
                new ChanceResult(new ItemStack(Items.BOWL, 2), 1.0F),
                new ChanceResult(new ItemStack(Items.BOWL, 3), 0.35F));

        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();
        List<ItemStack> secondaries = handler.getSecondaryOutputs(recipe, RegistryAccess.EMPTY);

        assertEquals(1, secondaries.size());
        assertEquals(Items.BOWL, secondaries.get(0).getItem());
        assertEquals(2, secondaries.get(0).getCount());
        assertFalse(handler.requiresTargetedProduction(recipe, new ItemStack(Items.BOWL)));
    }

    @Test
    void emptyResultBeforeRandomPrimaryDoesNotClaimGuaranteedProduction() {
        CuttingBoardRecipe recipe = recipe(
                ChanceResult.EMPTY,
                new ChanceResult(new ItemStack(Items.MELON_SLICE), 0.35F));

        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        assertEquals(Items.MELON_SLICE,
                handler.getResultItem(recipe, RegistryAccess.EMPTY).getItem());
        assertFalse(handler.hasDeterministicPrimaryOutput(recipe));
        assertFalse(handler.supportsDeterministicProjection(recipe));
        assertTrue(handler.requiresTargetedProduction(recipe, new ItemStack(Items.MELON_SLICE)));
    }

    @Test
    void emptyRandomEntryDoesNotMakeFixedRecipeRandom() {
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(ItemStack.EMPTY, 0.35F),
                new ChanceResult(new ItemStack(Items.MELON_SLICE, 4), 1.0F));

        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        assertTrue(handler.hasDeterministicPrimaryOutput(recipe));
        assertFalse(handler.hasRandomizedOutputs(recipe));
        assertTrue(handler.supportsDeterministicProjection(recipe));
        assertEquals(4, handler.getResultItem(recipe, RegistryAccess.EMPTY).getCount());
    }

    @Test
    void emptyRecipeHasNoGuaranteedPrimary() {
        CuttingBoardRecipe recipe = recipe(ChanceResult.EMPTY);
        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        assertTrue(handler.getResultItem(recipe, RegistryAccess.EMPTY).isEmpty());
        assertFalse(handler.hasDeterministicPrimaryOutput(recipe));
        assertFalse(handler.supportsDeterministicProjection(recipe));
    }

    @Test
    void nativeResultWithoutChanceIsGuaranteed() {
        ChanceResult result = ChanceResult.deserialize(JsonParser.parseString(
                "{\"item\":\"minecraft:melon_slice\",\"count\":4}"));
        CuttingBoardRecipe recipe = recipe(result);
        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        assertEquals(1.0F, result.getChance());
        assertEquals(4, handler.getResultItem(recipe, RegistryAccess.EMPTY).getCount());
        assertTrue(handler.hasDeterministicPrimaryOutput(recipe));
        assertFalse(handler.hasRandomizedOutputs(recipe));
        assertTrue(handler.supportsDeterministicProjection(recipe));
    }

    @Test
    void explicitNativeChanceRemainsProbabilistic() {
        ChanceResult result = ChanceResult.deserialize(JsonParser.parseString(
                "{\"item\":\"minecraft:melon_slice\",\"count\":4,\"chance\":0.35}"));
        CuttingBoardRecipe recipe = recipe(result);
        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        assertEquals(0.35F, result.getChance());
        assertFalse(handler.hasDeterministicPrimaryOutput(recipe));
        assertTrue(handler.hasRandomizedOutputs(recipe));
        assertTrue(handler.requiresTargetedProduction(recipe,
                IntegrationResourceKey.item(new ItemStack(Items.MELON_SLICE))));
        assertFalse(handler.supportsDeterministicProjection(recipe));
    }

    @Test
    void probabilitiesAtOrAboveOneGuaranteeEveryDeclaredUnit() {
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(new ItemStack(Items.MELON_SLICE, 4), 1.25F));
        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        assertTrue(handler.hasDeterministicPrimaryOutput(recipe));
        assertFalse(handler.hasRandomizedOutputs(recipe));
        assertFalse(handler.requiresTargetedProduction(recipe, new ItemStack(Items.MELON_SLICE)));
    }

    @Test
    void resourceTargetPreservesByproductNbtWhenCheckingProbability() {
        ItemStack guaranteed = new ItemStack(Items.BOWL);
        guaranteed.getOrCreateTag().putString("quality", "normal");
        ItemStack random = new ItemStack(Items.BOWL);
        random.getOrCreateTag().putString("quality", "rare");
        CuttingBoardRecipe recipe = recipe(
                new ChanceResult(new ItemStack(Items.MELON_SLICE), 1.0F),
                new ChanceResult(guaranteed, 1.0F),
                new ChanceResult(random, 0.35F));
        CuttingBoardRecipeHandler handler = new CuttingBoardRecipeHandler();

        assertFalse(handler.requiresTargetedProduction(recipe,
                IntegrationResourceKey.item(guaranteed)));
        assertTrue(handler.requiresTargetedProduction(recipe,
                IntegrationResourceKey.item(random)));
        List<ItemStack> secondaries = handler.getSecondaryOutputs(recipe, RegistryAccess.EMPTY);
        assertEquals(1, secondaries.size());
        assertTrue(ItemStack.isSameItemSameTags(guaranteed, secondaries.get(0)));
    }
}
