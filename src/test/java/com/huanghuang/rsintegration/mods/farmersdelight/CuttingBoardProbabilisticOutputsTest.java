package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
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
}
