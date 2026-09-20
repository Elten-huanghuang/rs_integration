package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
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
                List.of(new InputBufferPlanner.InputSlotSpec(
                        0, new ItemStack(Items.IRON_INGOT), 1, false, 64)),
                List.of(new InputBufferPlanner.OutputPortSpec(
                        0, new ItemStack(Items.IRON_NUGGET), 9)));

        assertEquals(64, plan.operations());
        assertEquals(64, plan.inputs().get(0).stack().getCount());
        assertEquals(576, plan.outputs().get(0).expected().getCount());
    }

    @Test
    void multiInputUsesTheTightestSlotCapacity() {
        InputBufferPlan plan = InputBufferPlanner.plan(64, 64,
                List.of(
                        new InputBufferPlanner.InputSlotSpec(
                                0, new ItemStack(Items.IRON_INGOT), 1, false, 64),
                        new InputBufferPlanner.InputSlotSpec(
                                1, new ItemStack(Items.COAL), 1, false, 8)),
                List.of());

        assertEquals(8, plan.operations());
        assertEquals(8, plan.inputs().get(0).stack().getCount());
        assertEquals(8, plan.inputs().get(1).stack().getCount());
    }

    @Test
    void reusableInputIsInstalledOnce() {
        InputBufferPlan plan = InputBufferPlanner.plan(32, 32,
                List.of(new InputBufferPlanner.InputSlotSpec(
                        2, new ItemStack(Items.BUCKET), 1, true, 1)),
                List.of());

        assertEquals(32, plan.operations());
        assertTrue(plan.inputs().get(0).reusable());
        assertEquals(1, plan.inputs().get(0).stack().getCount());
    }

    @Test
    void preservesExplicitInputSlotsAndOutputPorts() {
        InputBufferPlan plan = InputBufferPlanner.plan(4, 4,
                List.of(
                        new InputBufferPlanner.InputSlotSpec(
                                3, new ItemStack(Items.WHEAT), 2, false, 64),
                        new InputBufferPlanner.InputSlotSpec(
                                7, new ItemStack(Items.WATER_BUCKET), 1, true, 1)),
                List.of(
                        new InputBufferPlanner.OutputPortSpec(
                                5, new ItemStack(Items.BREAD), 1),
                        new InputBufferPlanner.OutputPortSpec(
                                9, new ItemStack(Items.WHEAT), 1)));

        assertEquals(List.of(3, 7), plan.inputs().stream()
                .map(InputBufferPlan.InputSlot::slot).toList());
        assertEquals(List.of(5, 9), plan.outputs().stream()
                .map(InputBufferPlan.OutputPort::port).toList());
        assertEquals(8, plan.inputs().get(0).stack().getCount());
        assertEquals(1, plan.inputs().get(1).stack().getCount());
    }

    @Test
    void rejectsDuplicatePhysicalSlots() {
        assertThrows(IllegalArgumentException.class, () -> InputBufferPlanner.plan(2, 2,
                List.of(
                        new InputBufferPlanner.InputSlotSpec(
                                0, new ItemStack(Items.IRON_INGOT), 1, false, 64),
                        new InputBufferPlanner.InputSlotSpec(
                                0, new ItemStack(Items.COAL), 1, false, 64)),
                List.of()));
    }

    @Test
    void preservesStableMaterialAndOutputIdentities() {
        InputBufferPlan plan = InputBufferPlanner.plan(3, 3,
                List.of(new InputBufferPlanner.InputSlotSpec(
                        "ore", 4, new ItemStack(Items.IRON_INGOT), 2, false, 64)),
                List.of(
                        new InputBufferPlanner.OutputPortSpec(
                                "product", 1, new ItemStack(Items.IRON_NUGGET), 1,
                                InputBufferPlan.OutputPort.Kind.PRIMARY),
                        new InputBufferPlanner.OutputPortSpec(
                                "slag", 2, new ItemStack(Items.FLINT), 1,
                                InputBufferPlan.OutputPort.Kind.SECONDARY)));

        InputBufferPlan.InputSlot input = plan.inputs().get(0);
        assertEquals("ore", input.entryId());
        assertEquals(2, input.perOperation());
        assertEquals(6, input.stack().getCount());

        assertEquals("product", plan.outputs().get(0).portId());
        assertEquals(1, plan.outputs().get(0).perOperation());
        assertEquals(InputBufferPlan.OutputPort.Kind.PRIMARY, plan.outputs().get(0).kind());
        assertEquals("slag", plan.outputs().get(1).portId());
        assertEquals(InputBufferPlan.OutputPort.Kind.SECONDARY, plan.outputs().get(1).kind());
    }

    @Test
    void rejectsDuplicateStableInputIdentity() {
        assertThrows(IllegalArgumentException.class, () -> InputBufferPlanner.plan(2, 2,
                List.of(
                        new InputBufferPlanner.InputSlotSpec(
                                "same", 0, new ItemStack(Items.IRON_INGOT), 1, false, 64),
                        new InputBufferPlanner.InputSlotSpec(
                                "same", 1, new ItemStack(Items.COAL), 1, false, 64)),
                List.of()));
    }
}
