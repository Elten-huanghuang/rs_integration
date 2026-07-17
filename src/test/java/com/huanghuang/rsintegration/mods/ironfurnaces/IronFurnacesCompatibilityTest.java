package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IronFurnacesCompatibilityTest extends BootstrapTest {

    @Test
    void bindingModesOnlyMatchTheirCorrespondingCookingType() {
        ModType furnace = register("ironfurnaces_furnace");
        ModType blast = register("ironfurnaces_blast_furnace");
        ModType smoker = register("ironfurnaces_smoker");
        ModType vanillaFurnace = register("vanilla_furnace");
        ModType vanillaBlast = register("vanilla_blast_furnace");
        ModType vanillaSmoker = register("vanilla_smoker");

        assertTrue(AltarBindingRegistry.isCompatibleMachineType(vanillaFurnace, furnace));
        assertFalse(AltarBindingRegistry.isCompatibleMachineType(vanillaBlast, furnace));
        assertFalse(AltarBindingRegistry.isCompatibleMachineType(vanillaSmoker, furnace));
        assertTrue(AltarBindingRegistry.isCompatibleMachineType(vanillaBlast, blast));
        assertTrue(AltarBindingRegistry.isCompatibleMachineType(vanillaSmoker, smoker));
        assertFalse(AltarBindingRegistry.isCompatibleMachineType(register("goety"), furnace));
    }

    @Test
    void concreteCookingRecipesMapToMatchingRecipeTypes() {
        assertEquals(RecipeType.SMELTING, IronFurnacesBatchDelegate.recipeType(
                new SmeltingRecipe(new ResourceLocation("test", "smelting"), "test",
                        net.minecraft.world.item.crafting.CookingBookCategory.MISC,
                        net.minecraft.world.item.crafting.Ingredient.EMPTY,
                        net.minecraft.world.item.ItemStack.EMPTY, 0, 200)));
        assertEquals(RecipeType.BLASTING, IronFurnacesBatchDelegate.recipeType(
                new BlastingRecipe(new ResourceLocation("test", "blasting"), "test",
                        net.minecraft.world.item.crafting.CookingBookCategory.MISC,
                        net.minecraft.world.item.crafting.Ingredient.EMPTY,
                        net.minecraft.world.item.ItemStack.EMPTY, 0, 100)));
        assertEquals(RecipeType.SMOKING, IronFurnacesBatchDelegate.recipeType(
                new SmokingRecipe(new ResourceLocation("test", "smoking"), "test",
                        net.minecraft.world.item.crafting.CookingBookCategory.MISC,
                        net.minecraft.world.item.crafting.Ingredient.EMPTY,
                        net.minecraft.world.item.ItemStack.EMPTY, 0, 100)));
    }

    @Test
    void bindingPrefixMigrationPreservesMachineIdentity() {
        String original = "ironfurnaces_furnace||block.ironfurnaces.diamond_furnace";

        assertEquals("ironfurnaces_blast_furnace||block.ironfurnaces.diamond_furnace",
                BindingStorage.replaceBlockKeyPrefix(original, IronFurnacesRSModule.BLAST_TYPE_ID));
        assertTrue(IronFurnaceBindingUpdater.isIronFurnaceBlockKey(original));
        assertTrue(IronFurnaceBindingUpdater.isIronFurnaceBlockKey(
                "ironfurnaces_smoker||block.ironfurnaces.diamond_furnace"));
        assertFalse(IronFurnaceBindingUpdater.isIronFurnaceBlockKey(
                "vanilla_furnace||block.minecraft.furnace"));
    }

    @Test
    void cookingRecipeTypesMapToBindingPrefixes() {
        assertEquals(IronFurnacesRSModule.TYPE_ID,
                IronFurnaceBindingUpdater.prefixFor(RecipeType.SMELTING));
        assertEquals(IronFurnacesRSModule.BLAST_TYPE_ID,
                IronFurnaceBindingUpdater.prefixFor(RecipeType.BLASTING));
        assertEquals(IronFurnacesRSModule.SMOKER_TYPE_ID,
                IronFurnaceBindingUpdater.prefixFor(RecipeType.SMOKING));
        assertNull(IronFurnaceBindingUpdater.prefixFor(RecipeType.CAMPFIRE_COOKING));
    }

    private static ModType register(String id) {
        return ModType.register(id, new String[0], new String[0], new String[0], () -> null);
    }
}
