package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputBufferContractTest extends BootstrapTest {

    @Test
    void contractPlansSlotAndPortIdentities() {
        InputBufferContract contract = new InputBufferContract(64,
                List.of(
                        new InputBufferContract.InputSlot(
                                "ore", 0, new ItemStack(Items.IRON_INGOT), 1, false, 64),
                        new InputBufferContract.InputSlot(
                                "catalyst", 4, new ItemStack(Items.BUCKET), 1, true, 1)),
                List.of(
                        new OutputContract.Port(
                                "product", 2, new ItemStack(Items.IRON_NUGGET), 9,
                                InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.SLOT),
                        new OutputContract.Port(
                                "byproduct", 6, new ItemStack(Items.FLINT), 1,
                                InputBufferPlan.OutputPort.Kind.SECONDARY, OutputContract.Source.WORLD)));

        InputBufferPlan plan = contract.plan(8);

        assertTrue(contract.enabled());
        assertEquals(8, plan.operations());
        assertEquals("ore", plan.inputs().get(0).entryId());
        assertEquals(8, plan.inputs().get(0).stack().getCount());
        assertEquals("catalyst", plan.inputs().get(1).entryId());
        assertEquals(1, plan.inputs().get(1).stack().getCount());
        assertEquals("product", plan.outputs().get(0).portId());
        assertEquals(72, plan.outputs().get(0).expected().getCount());
        assertEquals(InputBufferPlan.OutputPort.Kind.SECONDARY, plan.outputs().get(1).kind());
        assertEquals(OutputContract.Source.WORLD, plan.outputs().get(1).source());

        OutputContract outputContract = OutputContract.fromPlan(plan);
        assertEquals(List.of("product", "byproduct"), outputContract.ports().stream()
                .map(OutputContract.Port::portId).toList());
        assertEquals(OutputContract.Source.WORLD, outputContract.ports().get(1).source());
    }

    @Test
    void noneDoesNotProduceABufferPlan() {
        assertFalse(InputBufferContract.none().enabled());
        assertFalse(InputBufferContract.none().plan(4).enabled());
    }

    @Test
    void rejectsDuplicatePhysicalInputSlots() {
        assertThrows(IllegalArgumentException.class, () -> new InputBufferContract(2,
                List.of(
                        new InputBufferContract.InputSlot(
                                "first", 0, new ItemStack(Items.IRON_INGOT), 1, false, 64),
                        new InputBufferContract.InputSlot(
                                "second", 0, new ItemStack(Items.COAL), 1, false, 64)),
                List.of()));
    }

    @Test
    void resolvedInputsKeepThePlannedSlotsAndRejectCountMismatch() {
        InputBufferPlan plan = new InputBufferPlan(4,
                List.of(new InputBufferPlan.InputSlot(
                        "ore", 0, new ItemStack(Items.IRON_INGOT, 4), 1, false)),
                List.of());

        InputBufferPlan resolved = plan.withResolvedInputs(
                List.of(new ItemStack(Items.RAW_IRON, 4)));

        assertTrue(resolved.enabled());
        assertEquals(0, resolved.inputs().get(0).slot());
        assertTrue(resolved.inputs().get(0).stack().is(Items.RAW_IRON));
        assertFalse(plan.withResolvedInputs(List.of(new ItemStack(Items.RAW_IRON, 3))).enabled());
    }
}
