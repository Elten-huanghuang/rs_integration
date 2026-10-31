package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InputBufferPlannerTest extends BootstrapTest {
    @Test
    void plansSingleInputBatch() {
        InputBufferPlan plan = InputBufferPlanner.plan(64, 64,
                List.of(consumedInput(0, new ItemStack(Items.IRON_INGOT), 1, 64)),
                List.of(output(0, new ItemStack(Items.IRON_NUGGET), 9)));

        assertEquals(64, plan.operations());
        assertEquals(64, plan.inputs().get(0).stack().getCount());
        assertEquals(576, plan.outputs().get(0).perOperation() * plan.operations());
    }

    @Test
    void multiInputUsesTheTightestSlotCapacity() {
        InputBufferPlan plan = InputBufferPlanner.plan(64, 64,
                List.of(
                        consumedInput(0, new ItemStack(Items.IRON_INGOT), 1, 64),
                        consumedInput(1, new ItemStack(Items.COAL), 1, 8)),
                List.of());

        assertEquals(8, plan.operations());
        assertEquals(8, plan.inputs().get(0).stack().getCount());
        assertEquals(8, plan.inputs().get(1).stack().getCount());
    }

    @Test
    void reusableInputIsInstalledOnce() {
        InputBufferPlan plan = InputBufferPlanner.plan(32, 32,
                List.of(reusableInput(2, new ItemStack(Items.BUCKET), 1, 1)),
                List.of());

        assertEquals(32, plan.operations());
        assertTrue(plan.inputs().get(0).consumption().reusableWithinWorkerBatch());
        assertEquals(1, plan.inputs().get(0).stack().getCount());
    }

    @Test
    void preservesExplicitInputSlotsAndOutputPorts() {
        InputBufferPlan plan = InputBufferPlanner.plan(4, 4,
                List.of(
                        consumedInput(3, new ItemStack(Items.WHEAT), 2, 64),
                        reusableInput(7, new ItemStack(Items.WATER_BUCKET), 1, 1)),
                List.of(
                        output(5, new ItemStack(Items.BREAD), 1),
                        output(9, new ItemStack(Items.WHEAT), 1)));

        assertEquals(List.of(3, 7), plan.inputs().stream()
                .map(InputBufferPlan.InputSlot::slot).toList());
        assertEquals(List.of(5, 9), plan.outputs().stream()
                .map(OutputContract.Port::physicalPort).toList());
        assertEquals(8, plan.inputs().get(0).stack().getCount());
        assertEquals(1, plan.inputs().get(1).stack().getCount());
    }

    @Test
    void rejectsDuplicatePhysicalSlots() {
        assertThrows(IllegalArgumentException.class, () -> InputBufferPlanner.plan(2, 2,
                List.of(
                        consumedInput(0, new ItemStack(Items.IRON_INGOT), 1, 64),
                        consumedInput(0, new ItemStack(Items.COAL), 1, 64)),
                List.of()));
    }

    @Test
    void preservesStableMaterialAndOutputIdentities() {
        InputBufferPlan plan = InputBufferPlanner.plan(3, 3,
                List.of(new InputBufferContract.InputSlot(
                        "ore", 4, new ItemStack(Items.IRON_INGOT), 2, 64,
                        InputConsumption.consumed())),
                List.of(
                        new OutputContract.Port(
                                "product", 1, new ItemStack(Items.IRON_NUGGET), 1,
                                OutputContract.Kind.PRIMARY,
                                OutputContract.Source.SLOT, 1, 1,
                                OutputProbability.guaranteed(), false, null),
                        new OutputContract.Port(
                                "slag", 2, new ItemStack(Items.FLINT), 1,
                                OutputContract.Kind.SECONDARY,
                                OutputContract.Source.SLOT, 1, 1,
                                OutputProbability.guaranteed(), false, null)));

        InputBufferPlan.InputSlot input = plan.inputs().get(0);
        assertEquals("ore", input.entryId());
        assertEquals(2, input.perOperation());
        assertEquals(6, input.stack().getCount());

        assertEquals("product", plan.outputs().get(0).portId());
        assertEquals(1, plan.outputs().get(0).perOperation());
        assertEquals(OutputContract.Kind.PRIMARY, plan.outputs().get(0).kind());
        assertEquals("slag", plan.outputs().get(1).portId());
        assertEquals(OutputContract.Kind.SECONDARY, plan.outputs().get(1).kind());
    }

    @Test
    void rejectsDuplicateStableInputIdentity() {
        assertThrows(IllegalArgumentException.class, () -> InputBufferPlanner.plan(2, 2,
                List.of(
                        new InputBufferContract.InputSlot(
                                "same", 0, new ItemStack(Items.IRON_INGOT), 1, 64,
                                InputConsumption.consumed()),
                        new InputBufferContract.InputSlot(
                                "same", 1, new ItemStack(Items.COAL), 1, 64,
                                InputConsumption.consumed())),
                List.of()));
    }

    @Test
    void distributesOneMaterialAcrossIndependentLanes() {
        List<InputBufferContract.InputSlot> lanes = List.of(
                lane(3, 64), lane(4, 64));

        InputBufferPlan plan = InputBufferPlanner.plan(63, 128, lanes, List.of());

        assertEquals(63, plan.operations());
        assertEquals(List.of(32, 31), plan.inputs().stream()
                .map(input -> input.stack().getCount()).toList());
        assertEquals(1, InputBufferPlanner.plan(1, 128, lanes, List.of()).inputs().size());
        assertEquals(128, InputBufferPlanner.plan(200, 128, lanes, List.of()).operations());
    }

    @Test
    void distributedMaterialRespectsEachLaneAndOtherMaterialCapacity() {
        List<InputBufferContract.InputSlot> inputs = List.of(
                lane(3, 2), lane(4, 64),
                consumedInput(0, new ItemStack(Items.COAL), 1, 40));

        InputBufferPlan plan = InputBufferPlanner.plan(64, 64, inputs, List.of());

        assertEquals(40, plan.operations());
        assertEquals(List.of(2, 38, 40), plan.inputs().stream()
                .map(input -> input.stack().getCount()).toList());
        InputBufferPlan reversed = InputBufferPlanner.plan(64, 64,
                List.of(lane(3, 64), lane(4, 2)), List.of());
        assertEquals(List.of(62, 2), reversed.inputs().stream()
                .map(input -> input.stack().getCount()).toList());
    }

    @Test
    void reusableMaterialsCannotBeDuplicatedAcrossLanes() {
        assertThrows(IllegalArgumentException.class, () -> InputBufferPlanner.plan(2, 2,
                List.of(
                        new InputBufferContract.InputSlot(
                                "tool", 3, new ItemStack(Items.BUCKET), 1, 1,
                                InputConsumption.catalyst()),
                        new InputBufferContract.InputSlot(
                                "tool", 4, new ItemStack(Items.BUCKET), 1, 1,
                                InputConsumption.catalyst())), List.of()));
    }

    private static InputBufferContract.InputSlot lane(int slot, int capacity) {
        return new InputBufferContract.InputSlot("ore", slot,
                new ItemStack(Items.IRON_INGOT), 1, capacity, InputConsumption.consumed());
    }

    private static InputBufferContract.InputSlot consumedInput(
            int slot, ItemStack stack, int perOperation, int capacity) {
        return new InputBufferContract.InputSlot("legacy:input:" + slot, slot,
                stack, perOperation, capacity, InputConsumption.consumed());
    }

    private static InputBufferContract.InputSlot reusableInput(
            int slot, ItemStack stack, int perOperation, int capacity) {
        return new InputBufferContract.InputSlot("legacy:input:" + slot, slot,
                stack, perOperation, capacity, InputConsumption.catalyst());
    }

    private static OutputContract.Port output(
            int port, ItemStack stack, int perOperation) {
        return new OutputContract.Port("legacy:output:" + port, port,
                stack, perOperation, OutputContract.Kind.PRIMARY,
                OutputContract.Source.SLOT, perOperation, perOperation,
                OutputProbability.guaranteed(), false, null);
    }
}
