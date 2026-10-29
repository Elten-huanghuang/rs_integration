package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.mockito.MockedStatic;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class GoetyInfuserCompatibilityTest extends BootstrapTest {
    @Test
    void failureRecoversOnlyQueuedAndRemainingOwnedSlots() throws Exception {
        CursedInfuserBatchDelegate delegate = new CursedInfuserBatchDelegate();
        BlockEntity machine = mock(BlockEntity.class);
        ItemStack template = new ItemStack(Items.IRON_INGOT);
        template.getOrCreateTag().putString("owner", "order");
        ItemStack differentTag = new ItemStack(Items.IRON_INGOT);
        differentTag.getOrCreateTag().putString("owner", "external");
        List<ItemStack> slots = new ArrayList<>(List.of(
                template.copyWithCount(2), ItemStack.EMPTY, differentTag, new ItemStack(Items.GOLD_INGOT)));
        set(delegate, "inputTemplate", template);
        set(delegate, "activeMachine", machine);
        set(delegate, "queuedInput", template.copyWithCount(3));
        Field field = CursedInfuserBatchDelegate.class.getDeclaredField("activeOwnedSlots");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Integer> owned = (Set<Integer>) field.get(delegate);
        owned.addAll(List.of(0, 1, 2));
        try (MockedStatic<GoetyInfuserMachineSupport> support =
                     mockStatic(GoetyInfuserMachineSupport.class)) {
            support.when(() -> GoetyInfuserMachineSupport.recipeItems(machine)).thenReturn(slots);
            delegate.clearMachineState(machine, null);
        }
        assertEquals(2, delegate.failureRecoveredInputs().size());
        assertEquals(3, delegate.failureRecoveredInputs().get(0).getCount());
        assertEquals(1, delegate.failureRecoveredInputs().get(1).getCount());
        assertEquals(1, slots.get(0).getCount());
        assertEquals("external", slots.get(2).getTag().getString("owner"));
        assertEquals(Items.GOLD_INGOT, slots.get(3).getItem());
    }

    @Test
    void missingInfuserReturnsOnlyInputsNotYetPlaced() throws Exception {
        CursedInfuserBatchDelegate delegate = new CursedInfuserBatchDelegate();
        set(delegate, "queuedInput", new ItemStack(Items.IRON_INGOT, 3));
        delegate.clearMissingMachineState(null);
        assertEquals(3, delegate.failureRecoveredInputs().get(0).getCount());
    }

    @Test
    void cancelledInfuserDoesNotRemoveItemsFromReplacementMachine() throws Exception {
        CursedInfuserBatchDelegate delegate = new CursedInfuserBatchDelegate();
        set(delegate, "activeMachine", mock(BlockEntity.class));
        set(delegate, "queuedInput", new ItemStack(Items.IRON_INGOT, 2));
        set(delegate, "inputTemplate", new ItemStack(Items.IRON_INGOT));
        BlockEntity replacement = mock(BlockEntity.class);
        try (MockedStatic<GoetyInfuserMachineSupport> support =
                     mockStatic(GoetyInfuserMachineSupport.class)) {
            delegate.clearMachineState(replacement, null);
            support.verifyNoInteractions();
        }
        assertEquals(1, delegate.failureRecoveredInputs().size());
        assertEquals(2, delegate.failureRecoveredInputs().get(0).getCount());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

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

    @Test
    void bufferedInfuserLayoutKeepsWorldOutputAsOnePort() {
        InputBufferContract contract = new InputBufferContract(64,
                List.of(new InputBufferContract.InputSlot(
                        "material", 0, new ItemStack(Items.STONE), 1, 64,
                        InputConsumption.consumed())),
                List.of(new OutputContract.Port(
                        "primary", null, new ItemStack(Items.DIAMOND), 1,
                        OutputContract.Kind.PRIMARY, OutputContract.Source.WORLD)));
        InputBufferPlan plan = contract.plan(40);
        assertEquals(40, plan.operations());
        assertEquals(40, plan.inputs().get(0).stack().getCount());
        assertEquals(OutputContract.Source.WORLD, plan.outputs().get(0).source());
        assertEquals(40, plan.outputs().get(0).perOperation() * plan.operations());
    }
}
