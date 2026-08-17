package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyInfuserCompatibilityTest extends BootstrapTest {
    @Test
    void bindingIncludesAllThreeSharedRecipeMachines() {
        assertEquals(List.of(
                        "com.Polarice3.Goety.common.blocks.CursedInfuserBlock",
                        "com.Polarice3.Goety.common.blocks.GrimInfuserBlock",
                        "com.k1sak1.goetyawaken.common.blocks.DarkMenderBlock"),
                GoetyRSModule.CURSED_INFUSER_BLOCK_CLASSES);
        assertEquals(List.of(
                        "goety:cursed_infuser",
                        "goety:grim_infuser",
                        "goetyawaken:dark_mender"),
                GoetyRSModule.CURSED_INFUSER_BLOCK_IDS);
    }

    @Test
    void machineCapabilityMatrixMatchesNativeImplementations() {
        assertEquals(1, GoetyInfuserMachineSupport.Kind.CURSED.recipeCapacity());
        assertEquals(64, GoetyInfuserMachineSupport.Kind.GRIM.recipeCapacity());
        assertEquals(64, GoetyInfuserMachineSupport.Kind.DARK_MENDER.recipeCapacity());
        assertFalse(GoetyInfuserMachineSupport.Kind.CURSED.acceptsGrimRecipes());
        assertTrue(GoetyInfuserMachineSupport.Kind.GRIM.acceptsGrimRecipes());
        assertTrue(GoetyInfuserMachineSupport.Kind.DARK_MENDER.acceptsGrimRecipes());
    }

    @Test
    void parallelBatchUsesEvenShareWithoutExceedingFreeSlots() {
        assertEquals(43, GoetyInfuserMachineSupport.parallelBatchSize(128, 3, 64));
        assertEquals(12, GoetyInfuserMachineSupport.parallelBatchSize(64, 3, 12));
        assertEquals(1, GoetyInfuserMachineSupport.parallelBatchSize(64, 3, 1));
    }

    @Test
    void slotHelpersOnlySelectNewlyOccupiedRecipeSlot() {
        List<ItemStack> slots = new ArrayList<>(List.of(
                ItemStack.EMPTY, new ItemStack(Items.STONE), ItemStack.EMPTY));
        boolean[] before = {true, false, true};
        slots.set(2, new ItemStack(Items.DIRT));
        assertEquals(2, CursedInfuserBatchDelegate.findNewlyOccupiedSlot(slots, before));
        assertEquals(1, CursedInfuserBatchDelegate.countEmptySlots(slots));
    }

    @Test
    void adjacentInfusersHaveIndependentWorldOutputCaptureRegions() {
        assertFalse(CursedInfuserBatchDelegate.outputCaptureRegion(BlockPos.ZERO)
                .intersects(CursedInfuserBatchDelegate.outputCaptureRegion(BlockPos.ZERO.east())));
    }
}
