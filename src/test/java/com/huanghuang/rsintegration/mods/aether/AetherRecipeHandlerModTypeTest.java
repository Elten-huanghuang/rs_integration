package com.huanghuang.rsintegration.mods.aether;

import com.huanghuang.rsintegration.ModType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class AetherRecipeHandlerModTypeTest {
    @BeforeAll
    static void registerTypes() {
        AetherRSModule.INSTANCE.registerModType();
    }

    @Test
    void registeredRecipeTypesResolveToConcreteMachines() {
        assertMachineType("freezing", "aether_freezer");
        assertMachineType("incubation", "aether_incubator");
        assertMachineType("enchanting", "aether_altar");
        assertMachineType("repairing", "aether_altar");
    }

    @Test
    void unknownRecipeTypesKeepTheFamilyFallback() {
        assertNull(AetherRecipeHandler.machineTypeForRecipeType(new ResourceLocation("test", "freezing")));
        assertNull(AetherRecipeHandler.machineTypeForRecipeType(new ResourceLocation("aether", "unknown")));
        assertEquals("aether", new AetherRecipeHandler().modType(mock(Recipe.class)).id());
    }

    private static void assertMachineType(String recipeType, String expectedMachine) {
        ModType machine = AetherRecipeHandler.machineTypeForRecipeType(new ResourceLocation("aether", recipeType));
        assertEquals(expectedMachine, machine.id());
    }
}
