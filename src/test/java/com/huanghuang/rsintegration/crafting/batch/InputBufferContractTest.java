package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class InputBufferContractTest extends BootstrapTest {

    @Test
    void keepsProbabilisticOutputMetadataWhenBuildingDispatchPlan() {
        InputBufferContract contract = new InputBufferContract(16,
                List.of(new InputBufferContract.InputSlot(
                        "input", 0, new ItemStack(Items.IRON_INGOT), 1, 64,
                        InputConsumption.consumed())),
                List.of(new OutputContract.Port(
                        "bonus", 2, new ItemStack(Items.DIAMOND), 0,
                        OutputContract.Kind.SECONDARY,
                        OutputContract.Source.SLOT, 0, 2,
                        OutputProbability.probabilistic(0.25D, 4, "bonus"),
                        false, null)));

        OutputContract.Port output = contract.plan(4).outputs().get(0);

        assertEquals(2, output.maximumPerOperation());
        assertEquals(OutputProbability.Mode.PROBABILISTIC, output.probability().mode());
    }

    @Test
    void contractPlansSlotAndPortIdentities() {
        InputBufferContract contract = new InputBufferContract(64,
                List.of(
                        new InputBufferContract.InputSlot(
                                "ore", 0, new ItemStack(Items.IRON_INGOT), 1, 64,
                                InputConsumption.consumed()),
                        new InputBufferContract.InputSlot(
                                "catalyst", 4, new ItemStack(Items.BUCKET), 1, 1,
                                InputConsumption.catalyst())),
                List.of(
                        new OutputContract.Port(
                                "product", 2, new ItemStack(Items.IRON_NUGGET), 9,
                                OutputContract.Kind.PRIMARY,
                                OutputContract.Source.SLOT, 9, 9,
                                OutputProbability.guaranteed(), false, null),
                        new OutputContract.Port(
                                "byproduct", 6, new ItemStack(Items.FLINT), 1,
                                OutputContract.Kind.SECONDARY,
                                OutputContract.Source.WORLD, 1, 1,
                                OutputProbability.guaranteed(), false, null)));

        InputBufferPlan plan = contract.plan(8);

        assertTrue(contract.enabled());
        assertEquals(8, plan.operations());
        assertEquals("ore", plan.inputs().get(0).entryId());
        assertEquals(8, plan.inputs().get(0).stack().getCount());
        assertEquals(InputConsumption.Kind.CONSUMED,
                plan.inputs().get(0).consumption().kind());
        assertEquals("catalyst", plan.inputs().get(1).entryId());
        assertEquals(1, plan.inputs().get(1).stack().getCount());
        assertEquals(InputConsumption.Kind.CATALYST,
                plan.inputs().get(1).consumption().kind());
        assertEquals("product", plan.outputs().get(0).portId());
        assertEquals(72, plan.outputs().get(0).perOperation() * plan.operations());
        assertEquals(OutputContract.Kind.SECONDARY, plan.outputs().get(1).kind());
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
                                "first", 0, new ItemStack(Items.IRON_INGOT), 1, 64,
                                InputConsumption.consumed()),
                        new InputBufferContract.InputSlot(
                                "second", 0, new ItemStack(Items.COAL), 1, 64,
                                InputConsumption.consumed())),
                List.of()));
    }

    @Test
    void resolvedInputsKeepThePlannedSlotsAndRejectCountMismatch() {
        InputBufferPlan plan = new InputBufferPlan(4,
                List.of(new InputBufferPlan.InputSlot(
                        "ore", 0, new ItemStack(Items.IRON_INGOT, 4), 1,
                        InputConsumption.consumed())),
                List.of());

        InputBufferPlan resolved = plan.withResolvedInputs(
                List.of(new ItemStack(Items.RAW_IRON, 4)));

        assertTrue(resolved.enabled());
        assertEquals(0, resolved.inputs().get(0).slot());
        assertTrue(resolved.inputs().get(0).stack().is(Items.RAW_IRON));
        assertFalse(plan.withResolvedInputs(List.of(new ItemStack(Items.RAW_IRON, 3))).enabled());
    }

    @Test
    void oneReservationBindsBothPhysicalLanesWithoutDuplicatingItems() {
        InputBufferContract contract = new InputBufferContract(64,
                List.of(
                        new InputBufferContract.InputSlot(
                                "legacy:material:0", 3, new ItemStack(Items.IRON_INGOT), 1, 64,
                                InputConsumption.consumed()),
                        new InputBufferContract.InputSlot(
                                "legacy:material:0", 4, new ItemStack(Items.IRON_INGOT), 1, 64,
                                InputConsumption.consumed())), List.of());
        ItemStack reserved = new ItemStack(Items.RAW_IRON, 63);
        reserved.getOrCreateTag().putString("batch", "test");
        MaterialPlan materials = MaterialPlan.fromLegacy(List.of(
                new IngredientSpec(Ingredient.of(Items.RAW_IRON), 1)));

        OperationStartContext context = OperationStartContext.chainReserved(
                mock(ServerPlayer.class), new ExtractionLedger(), materials,
                List.of(reserved), contract.plan(63));

        assertTrue(context.buffered());
        assertEquals(List.of(32, 31), context.inputBufferPlan().inputs().stream()
                .map(input -> input.stack().getCount()).toList());
        assertTrue(context.inputBufferPlan().inputs().stream()
                .allMatch(input -> input.stack().is(Items.RAW_IRON)
                        && "test".equals(input.stack().getTag().getString("batch"))));
        assertEquals(63, reserved.getCount());
        for (int count : List.of(62, 64)) {
            assertFalse(contract.plan(63).withResolvedMaterials(List.of(
                    new OperationStartContext.ReservedMaterial(
                            "legacy:material:0", reserved.copyWithCount(count)))).enabled());
        }
    }
}
