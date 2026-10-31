package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.RecipeOutputSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.crafting.PartialNBTIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CraftingResolverSelfConsumptionTest extends BootstrapTest {

    @Test
    void outputInBroadTagIsNotTreatedAsSelfConsumption() {
        Ingredient seeds = Ingredient.of(Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS);

        assertFalse(CraftingResolver.consumesOutput(
                seeds, new ItemStack(Items.WHEAT_SEEDS)));
    }

    @Test
    void exactOutputInputRemainsSelfConsumption() {
        Ingredient seeds = Ingredient.of(Items.WHEAT_SEEDS);

        assertTrue(CraftingResolver.consumesOutput(
                seeds, new ItemStack(Items.WHEAT_SEEDS)));
    }

    @Test
    void differentFluidTokensAreNotSelfConsumption() {
        ItemStack copper = fluidToken("embers:molten_copper", 2);
        ItemStack dawnstone = fluidToken("embers:molten_dawnstone", 4);

        assertFalse(CraftingResolver.consumesOutput(
                StrictNBTIngredient.of(copper), dawnstone));
        assertFalse(CraftingResolver.consumesOutput(
                PartialNBTIngredient.of(copper.getItem(), copper.getTag()), dawnstone));
    }

    @Test
    void sameFluidTokenRemainsSelfConsumptionRegardlessOfQuantity() {
        ItemStack dawnstone = fluidToken("embers:molten_dawnstone", 1);

        assertTrue(CraftingResolver.consumesOutput(
                StrictNBTIngredient.of(dawnstone), dawnstone.copyWithCount(4)));
    }

    @Test
    void vanillaIngredientStillIgnoresDisplayNbtForSelfConsumption() {
        ItemStack copper = fluidToken("embers:molten_copper", 1);
        ItemStack dawnstone = fluidToken("embers:molten_dawnstone", 4);

        assertTrue(CraftingResolver.consumesOutput(Ingredient.of(copper), dawnstone));
    }

    @Test
    void mixingDifferentFluidsKeepsTheFullOutputAsNetYield() {
        Recipe<?> recipe = mock(Recipe.class);
        when(recipe.getId()).thenReturn(new ResourceLocation("embers", "mixing/molten_dawnstone"));
        ItemStack copper = fluidToken("embers:molten_copper", 1);
        ItemStack gold = fluidToken("embers:molten_gold", 1);
        ItemStack dawnstone = fluidToken("embers:molten_dawnstone", 4);
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.getIngredients(any())).thenReturn(List.of(
                new IngredientSpec(StrictNBTIngredient.of(copper), 2),
                new IngredientSpec(StrictNBTIngredient.of(gold), 2)));
        when(handler.getRecursiveScaledIngredients(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(handler.getDeclaredOutputs(any(), any())).thenReturn(List.of(
                RecipeOutputSpec.item(MaterialKey.of(dawnstone), 4, OutputKind.PRIMARY)));
        RecipeIndex.Entry entry = new RecipeIndex.Entry(
                recipe, ModType.GENERIC, new ResourceLocation("embers", "mixing"));

        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));

            assertEquals(4, CraftingResolver.netGainPerBatch(
                    entry, dawnstone, RegistryAccess.EMPTY));
        }
    }

    @Test
    void guaranteedSecondaryOutputCountsTowardNetYield() {
        Recipe<?> recipe = mock(Recipe.class);
        when(recipe.getId()).thenReturn(new ResourceLocation("test", "secondary_diamond"));
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        when(handler.getDeclaredOutputs(any(), any())).thenReturn(List.of(
                RecipeOutputSpec.item(
                        MaterialKey.of(diamond), 1, OutputKind.PRIMARY),
                RecipeOutputSpec.item(
                        MaterialKey.of(diamond), 1, OutputKind.SECONDARY)));
        RecipeIndex.Entry entry = new RecipeIndex.Entry(
                recipe, ModType.GENERIC, new ResourceLocation("minecraft", "crafting"));

        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));

            assertEquals(2L, CraftingResolver.declaredOutputCount(
                    entry, diamond, RegistryAccess.EMPTY));
        }
    }

    @Test
    void secondaryOutputOffsetsSelfConsumptionForNetYield() {
        Recipe<?> recipe = mock(Recipe.class);
        when(recipe.getId()).thenReturn(new ResourceLocation("test", "amplifying_secondary"));
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        when(handler.getIngredients(any())).thenReturn(List.of(
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 1)));
        when(handler.getRecursiveScaledIngredients(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(handler.getDeclaredOutputs(any(), any())).thenReturn(List.of(
                RecipeOutputSpec.item(
                        MaterialKey.of(diamond), 1, OutputKind.PRIMARY),
                RecipeOutputSpec.item(
                        MaterialKey.of(diamond), 1, OutputKind.SECONDARY)));
        RecipeIndex.Entry entry = new RecipeIndex.Entry(
                recipe, ModType.GENERIC, new ResourceLocation("minecraft", "crafting"));

        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));

            assertEquals(1, CraftingResolver.netGainPerBatch(
                    entry, diamond, RegistryAccess.EMPTY));
        }
    }

    @Test
    void broadTagDoesNotTurnPositiveOutputIntoFalseAmplification() {
        Ingredient broad = Ingredient.of(Items.DIAMOND, Items.COAL);
        List<IngredientSpec> specs = List.of(
                new IngredientSpec(broad, 1));

        assertFalse(SelfAmplifyingRecipePolicy.isSelfAmplifying(
                specs, new ItemStack(Items.DIAMOND, 3)));
    }

    private static ItemStack fluidToken(String fluidId, int count) {
        // 使用同一种原版物品承载不同流体身份，复现内部凭据共用 Item 的情况。
        ItemStack token = new ItemStack(Items.PAPER, count);
        token.getOrCreateTag().putString("FluidName", fluidId);
        token.getTag().putInt("Amount", 1);
        return token;
    }
}
