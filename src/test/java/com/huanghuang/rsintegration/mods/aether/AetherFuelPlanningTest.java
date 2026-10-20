package com.huanghuang.rsintegration.mods.aether;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AetherFuelPlanningTest extends BootstrapTest {
    @Test
    void freezerPlanningUsesIcestoneAndItsFourHundredTickValue() {
        AetherFurnaceBatchDelegate.FuelProfile profile =
                AetherFurnaceBatchDelegate.fuelProfileForRecipe(new FreezingRecipe());

        assertEquals("aether:icestone", profile.itemId());
        assertEquals(400, profile.burnDuration());
        assertEquals(DemandRole.FUEL, profile.consumption().demandRole());
        assertEquals(2, AetherFurnaceBatchDelegate.fuelAmountForRecipe(
                new FreezingRecipe(), 3));
    }

    @Test
    void specializedAetherMachineTypesReachFuelPlanning() {
        assertTrue(AetherFurnaceBatchDelegate.isAetherMachineType("aether_freezer"));
        assertTrue(AetherFurnaceBatchDelegate.isAetherMachineType("aether_altar"));
        assertTrue(AetherFurnaceBatchDelegate.isAetherMachineType("aether_incubator"));
        assertFalse(AetherFurnaceBatchDelegate.isAetherMachineType("aetherworks_anvil"));
    }

    @Test
    void unrelatedAetherRecipesDoNotReceiveFreezerFuel() {
        assertNull(AetherFurnaceBatchDelegate.fuelProfileForRecipe(new OtherRecipe()));
    }

    public static class FreezingRecipe implements Recipe<Container> {
        public int getCookTime() {
            return 200;
        }

        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public ItemStack assemble(Container container, RegistryAccess access) {
            return ItemStack.EMPTY;
        }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public ItemStack getResultItem(RegistryAccess access) { return ItemStack.EMPTY; }
        @Override public ResourceLocation getId() { return new ResourceLocation("aether", "test_freezing"); }
        @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
        @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    }

    private static final class OtherRecipe extends FreezingRecipe {
        @Override public ResourceLocation getId() { return new ResourceLocation("aether", "other"); }
    }
}
