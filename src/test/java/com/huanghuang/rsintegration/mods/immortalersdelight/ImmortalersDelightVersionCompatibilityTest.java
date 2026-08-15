package com.huanghuang.rsintegration.mods.immortalersdelight;

import com.huanghuang.rsintegration.recipe.EnchantalCoolerRecipeHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmortalersDelightVersionCompatibilityTest extends BootstrapTest {

    @Test
    void recognizesLegacyCurrentAndNewCoolerRecipeClasses() {
        assertTrue(EnchantalCoolerRecipeHandler.isSupportedRecipeClassName(
                "com.renyigesai.immortalers_delight.recipe.EnchantalCoolerRecipe"));
        assertTrue(EnchantalCoolerRecipeHandler.isSupportedRecipeClassName(
                "com.renyigesai.immortalers_delight.recipe.PillagerKnifeAddPotionRecipe"));
        assertFalse(EnchantalCoolerRecipeHandler.isSupportedRecipeClassName(
                "com.renyigesai.immortalers_delight.recipe.HotSpringRecipe"));
    }

    @Test
    void hotSpringRecipeClassHasItsOwnVirtualRoute() {
        assertTrue(ImmortalersDelightHotSpringRecipeHandler.isSupportedRecipeClassName(
                "com.renyigesai.immortalers_delight.recipe.HotSpringRecipe"));
        assertFalse(ImmortalersDelightHotSpringRecipeHandler.isSupportedRecipeClassName(
                "com.renyigesai.immortalers_delight.recipe.EnchantalCoolerRecipe"));
    }

    @Test
    void coolerValidationIncludesTheContainerSlot() {
        ItemStackHandler handler = new ItemStackHandler(7);
        handler.setStackInSlot(0, new ItemStack(Items.EGG));
        handler.setStackInSlot(4, new ItemStack(Items.BOWL));

        SimpleContainer input = EnchantalCoolerBatchDelegate.createRecipeInput(handler);

        assertEquals(5, input.getContainerSize());
        assertTrue(input.getItem(0).is(Items.EGG));
        assertTrue(input.getItem(4).is(Items.BOWL));
    }

    @Test
    void coolerAcceptsRuntimeNbtAddedByNewSpecialRecipe() {
        ItemStack declared = new ItemStack(Items.IRON_SWORD);
        ItemStack coated = declared.copy();
        CompoundTag tag = new CompoundTag();
        tag.putInt("potion_coating_count", 3);
        coated.setTag(tag);

        assertTrue(EnchantalCoolerBatchDelegate.matchesExpectedOutput(coated, declared));
        assertFalse(EnchantalCoolerBatchDelegate.matchesExpectedOutput(
                new ItemStack(Items.DIAMOND_SWORD), declared));
    }

    @Test
    void plannedContainerIsSplitFromOrderedMachineInputs() {
        ItemStack taggedContainer = new ItemStack(Items.IRON_SWORD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("potion_coating_count", 1);
        taggedContainer.setTag(tag);

        EnchantalCoolerBatchDelegate.PreparedMaterials prepared =
                EnchantalCoolerBatchDelegate.splitPreparedMaterials(List.of(
                                new ItemStack(Items.APPLE),
                                new ItemStack(Items.CARROT),
                                taggedContainer),
                        2, new ItemStack(Items.IRON_SWORD));

        assertEquals(2, prepared.inputs().size());
        assertTrue(prepared.inputs().get(0).is(Items.APPLE));
        assertTrue(prepared.inputs().get(1).is(Items.CARROT));
        assertTrue(prepared.container().is(Items.IRON_SWORD));
        assertEquals(tag, prepared.container().getTag());
    }

    @Test
    void missingOrMisorderedContainerCannotReachTheMachine() {
        assertEquals(null, EnchantalCoolerBatchDelegate.splitPreparedMaterials(
                List.of(new ItemStack(Items.APPLE)), 1, new ItemStack(Items.GLASS_BOTTLE)));
        assertEquals(null, EnchantalCoolerBatchDelegate.splitPreparedMaterials(
                List.of(new ItemStack(Items.GLASS_BOTTLE), new ItemStack(Items.APPLE)),
                1, new ItemStack(Items.GLASS_BOTTLE)));
    }
}
