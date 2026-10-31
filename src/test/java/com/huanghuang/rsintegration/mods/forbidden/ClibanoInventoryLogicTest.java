package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClibanoInventoryLogicTest extends BootstrapTest {

    @Test
    void choosesOnlyIdleInputLane() {
        assertEquals(3, ClibanoInventoryLogic.chooseInputSlot(ItemStack.EMPTY, ItemStack.EMPTY, 0, 0));
        assertEquals(4, ClibanoInventoryLogic.chooseInputSlot(new ItemStack(Items.STONE), ItemStack.EMPTY, 0, 0));
        assertEquals(4, ClibanoInventoryLogic.chooseInputSlot(ItemStack.EMPTY, ItemStack.EMPTY, 1, 0));
        assertEquals(3, ClibanoInventoryLogic.chooseInputSlot(ItemStack.EMPTY, ItemStack.EMPTY, 0, 1));
        assertEquals(-1, ClibanoInventoryLogic.chooseInputSlot(ItemStack.EMPTY, ItemStack.EMPTY, 1, 1));
        assertEquals(-1, ClibanoInventoryLogic.chooseInputSlot(
                new ItemStack(Items.STONE), new ItemStack(Items.DIRT), 0, 0));
    }

    @Test
    void countsExpectedOutputAcrossBothSlots() {
        ItemStack expected = new ItemStack(Items.IRON_INGOT);
        assertEquals(5, ClibanoInventoryLogic.countMatching(List.of(
                expected.copyWithCount(2),
                expected.copyWithCount(3),
                new ItemStack(Items.GOLD_INGOT)), expected));
    }

    @Test
    void refundsOnlyOperationOwnedFuelDelta() {
        ItemStack baseline = new ItemStack(Items.COAL, 5);
        assertEquals(2, ClibanoInventoryLogic.refundableAddedCount(
                baseline, 3, new ItemStack(Items.COAL, 7)));
        assertEquals(0, ClibanoInventoryLogic.refundableAddedCount(
                baseline, 3, new ItemStack(Items.COAL, 4)));
        assertEquals(0, ClibanoInventoryLogic.refundableAddedCount(
                baseline, 3, new ItemStack(Items.CHARCOAL, 7)));
    }

    @Test
    void higherFireTierSatisfiesLowerRequirement() {
        assertTrue(ClibanoInventoryLogic.fireSatisfies(2, 1));
        assertTrue(ClibanoInventoryLogic.fireSatisfies(1, 1));
        assertFalse(ClibanoInventoryLogic.fireSatisfies(0, 1));
    }

    @Test
    void bufferedOperationCapacityRespectsInputAndOutputLimits() {
        assertEquals(16, ClibanoInventoryLogic.bufferedOperationCapacity(
                64, 1, 64, 4, 64));
    }

    @Test
    void configuredFuelWinsOverStorageIterationOrder() {
        var selection = ClibanoInventoryLogic.selectFuel(
                List.of(new ItemStack(Items.BAMBOO, 64), new ItemStack(Items.COAL, 4)),
                List.of("minecraft:coal", "minecraft:charcoal"), 1600,
                stack -> stack.is(Items.COAL) ? 1600 : stack.is(Items.BAMBOO) ? 50 : 0);

        assertNotNull(selection);
        assertTrue(selection.fuel().is(Items.COAL));
        assertEquals(1, selection.amount());
        assertFalse(selection.partial());
    }

    @Test
    void existingFuelOnlyTopsUpTheMissingAmount() {
        assertEquals(1, ClibanoInventoryLogic.fuelToAdd(3200, 1600, 1));
        assertEquals(0, ClibanoInventoryLogic.fuelToAdd(1600, 1600, 1));
        assertEquals(Integer.MAX_VALUE,
                ClibanoInventoryLogic.fuelToAdd(1600, 0, 1));
    }

    @Test
    void declaresAndLoadsBothIdleInputSlots() {
        ItemStackHandler inventory = new ItemStackHandler(7);
        InputBufferContract contract = contract(inventory, 0, 0, 1);
        assertEquals(List.of(3, 4), contract.inputs().stream()
                .map(InputBufferContract.InputSlot::slot).toList());

        InputBufferPlan plan = bind(contract.plan(63));
        assertTrue(ClibanoInventoryLogic.placeInputs(inventory, inventory, plan.inputs()));
        assertEquals(32, inventory.getStackInSlot(3).getCount());
        assertEquals(31, inventory.getStackInSlot(4).getCount());
        assertEquals(63, plan.operations());
    }

    @Test
    void occupiedOrRunningLaneIsExcluded() {
        ItemStackHandler inventory = new ItemStackHandler(7);
        inventory.setStackInSlot(3, new ItemStack(Items.GOLD_ORE));
        assertEquals(List.of(4), contract(inventory, 0, 0, 1).inputs().stream()
                .map(InputBufferContract.InputSlot::slot).toList());
        assertFalse(contract(inventory, 0, 1, 1).enabled());
        inventory.setStackInSlot(3, ItemStack.EMPTY);
        assertEquals(List.of(3), contract(inventory, 0, 1, 1).inputs().stream()
                .map(InputBufferContract.InputSlot::slot).toList());
        assertEquals(List.of(4), contract(inventory, 1, 0, 1).inputs().stream()
                .map(InputBufferContract.InputSlot::slot).toList());
    }

    @Test
    void onlySecondLaneIsLoadedWhenFirstLaneIsOccupied() {
        ItemStackHandler inventory = new ItemStackHandler(7);
        inventory.setStackInSlot(3, new ItemStack(Items.GOLD_ORE, 7));
        InputBufferPlan plan = bind(contract(inventory, 0, 0, 1).plan(10));

        assertTrue(ClibanoInventoryLogic.placeInputs(inventory, inventory, plan.inputs()));
        assertEquals(7, inventory.getStackInSlot(3).getCount());
        assertTrue(inventory.getStackInSlot(3).is(Items.GOLD_ORE));
        assertEquals(10, inventory.getStackInSlot(4).getCount());
        assertEquals(10, ClibanoInventoryLogic.recoverInputs(inventory, plan.inputs())
                .stream().mapToInt(ItemStack::getCount).sum());
        assertEquals(7, inventory.getStackInSlot(3).getCount());
    }

    @Test
    void singleOperationUsesOneLaneAndOneItem() {
        ItemStackHandler inventory = new ItemStackHandler(7);
        InputBufferPlan plan = bind(contract(inventory, 0, 0, 1).plan(1));

        assertEquals(1, plan.inputs().size());
        assertTrue(ClibanoInventoryLogic.placeInputs(inventory, inventory, plan.inputs()));
        assertEquals(1, inventory.getStackInSlot(3).getCount());
        assertTrue(inventory.getStackInSlot(4).isEmpty());
    }

    @Test
    void sharedOutputCapacityLimitsTheWholeBatch() {
        InputBufferPlan plan = contract(new ItemStackHandler(7), 0, 0, 4).plan(64);
        assertEquals(32, plan.operations());
        assertEquals(List.of(16, 16), plan.inputs().stream()
                .map(input -> input.stack().getCount()).toList());
        assertEquals(1, plan.outputs().size());
        assertEquals(128, plan.outputs().get(0).expectedCount(plan.operations()));
    }

    @Test
    void inputCapacityCanBeSharedUnevenly() {
        ItemStackHandler inventory = new ItemStackHandler(7) {
            @Override
            public int getSlotLimit(int slot) {
                return slot == 3 ? 2 : 64;
            }
        };
        InputBufferPlan plan = bind(contract(inventory, 0, 0, 1).plan(64));
        assertTrue(ClibanoInventoryLogic.placeInputs(inventory, inventory, plan.inputs()));
        assertEquals(2, inventory.getStackInSlot(3).getCount());
        assertEquals(62, inventory.getStackInSlot(4).getCount());
    }

    @Test
    void finishingOneLaneStillWaitsForTheOtherLane() {
        ItemStackHandler inventory = new ItemStackHandler(7);
        InputBufferPlan plan = bind(contract(inventory, 0, 0, 1).plan(3));
        assertTrue(ClibanoInventoryLogic.placeInputs(inventory, inventory, plan.inputs()));
        inventory.setStackInSlot(4, ItemStack.EMPTY);
        assertTrue(ClibanoInventoryLogic.hasRemainingInputs(inventory, plan.inputs()));
        inventory.setStackInSlot(3, ItemStack.EMPTY);
        assertFalse(ClibanoInventoryLogic.hasRemainingInputs(inventory, plan.inputs()));
    }

    @Test
    void cancellationRecoversOnlyRemainingInputsAcrossBothLanes() {
        ItemStackHandler inventory = new ItemStackHandler(7);
        InputBufferPlan plan = bind(contract(inventory, 0, 0, 1).plan(10));
        assertTrue(ClibanoInventoryLogic.placeInputs(inventory, inventory, plan.inputs()));
        inventory.extractItem(3, 2, false);
        inventory.extractItem(4, 1, false);
        inventory.setStackInSlot(2, new ItemStack(Items.COAL, 5));
        inventory.setStackInSlot(5, new ItemStack(Items.IRON_INGOT, 3));

        List<ItemStack> recovered = ClibanoInventoryLogic.recoverInputs(inventory, plan.inputs());

        assertEquals(7, recovered.stream().mapToInt(ItemStack::getCount).sum());
        assertTrue(inventory.getStackInSlot(3).isEmpty());
        assertTrue(inventory.getStackInSlot(4).isEmpty());
        assertEquals(5, inventory.getStackInSlot(2).getCount());
        assertEquals(3, inventory.getStackInSlot(5).getCount());
        assertTrue(ClibanoInventoryLogic.recoverInputs(inventory, plan.inputs()).isEmpty());
    }

    @Test
    void secondLaneInsertionFailureRollsBackBothLanes() {
        ItemStackHandler inventory = new ItemStackHandler(7) {
            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (slot == 4 && !simulate) {
                    super.insertItem(slot, stack.copyWithCount(1), false);
                    return stack.copyWithCount(stack.getCount() - 1);
                }
                return super.insertItem(slot, stack, simulate);
            }
        };
        InputBufferPlan plan = bind(contract(inventory, 0, 0, 1).plan(10));

        assertFalse(ClibanoInventoryLogic.placeInputs(inventory, inventory, plan.inputs()));
        assertTrue(inventory.getStackInSlot(3).isEmpty());
        assertTrue(inventory.getStackInSlot(4).isEmpty());
    }

    @Test
    void occupiedSecondLaneRejectsBeforePlacingFirstLane() {
        ItemStackHandler inventory = new ItemStackHandler(7);
        InputBufferPlan plan = bind(contract(inventory, 0, 0, 1).plan(10));
        inventory.setStackInSlot(4, new ItemStack(Items.GOLD_ORE));

        assertFalse(ClibanoInventoryLogic.placeInputs(inventory, inventory, plan.inputs()));
        assertTrue(inventory.getStackInSlot(3).isEmpty());
        assertTrue(inventory.getStackInSlot(4).is(Items.GOLD_ORE));
    }

    private static InputBufferContract contract(
            ItemStackHandler inventory, int firstProgress, int secondProgress, int outputCount) {
        return ClibanoInventoryLogic.inputBufferContract(
                inventory, firstProgress, secondProgress, 64,
                new IngredientSpec(Ingredient.of(Items.RAW_IRON), 1),
                new ItemStack(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT, outputCount));
    }

    private static InputBufferPlan bind(InputBufferPlan plan) {
        return plan.withResolvedMaterials(List.of(new OperationStartContext.ReservedMaterial(
                "legacy:material:0", new ItemStack(Items.RAW_IRON, plan.operations()))));
    }
}
