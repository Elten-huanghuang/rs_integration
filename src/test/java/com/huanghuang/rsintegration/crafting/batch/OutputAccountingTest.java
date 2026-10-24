package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutputAccountingTest extends BootstrapTest {

    @Test
    void capturedProductionWaitsForTheBatchInsteadOfTheSingleDropPrototype() {
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.getExpectedOutput()).thenReturn(new ItemStack(Items.IRON_INGOT));
        when(delegate.getExpectedProduction()).thenReturn(
                new IBatchDelegate.ExpectedProduction(new ItemStack(Items.IRON_INGOT), 6));

        assertFalse(OutputAccounting.hasCapturedExpectedProduction(
                delegate, List.of(new ItemStack(Items.IRON_INGOT))));
        assertFalse(OutputAccounting.hasCapturedExpectedProduction(
                delegate, List.of(new ItemStack(Items.IRON_INGOT, 5), new ItemStack(Items.GOLD_INGOT))));
        assertTrue(OutputAccounting.hasCapturedExpectedProduction(
                delegate, List.of(new ItemStack(Items.IRON_INGOT, 4), new ItemStack(Items.IRON_INGOT, 2))));
    }

    @Test
    void legacyWorldOutputStillRequiresItsEntireDeclaredStack() {
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.getExpectedOutput()).thenReturn(new ItemStack(Items.IRON_INGOT, 2));

        assertFalse(OutputAccounting.hasCapturedExpectedProduction(
                delegate, List.of(new ItemStack(Items.IRON_INGOT))));
        assertTrue(OutputAccounting.hasCapturedExpectedProduction(
                delegate, List.of(new ItemStack(Items.IRON_INGOT, 2))));
        assertFalse(OutputAccounting.hasCapturedExpectedProduction(
                mock(IBatchDelegate.class), List.of(new ItemStack(Items.IRON_INGOT))));
    }

    @Test
    void capturedProductionKeepsRuntimeNbtForFinalSettlement() {
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.getExpectedProduction()).thenReturn(
                new IBatchDelegate.ExpectedProduction(new ItemStack(Items.IRON_INGOT), 2));
        ItemStack captured = new ItemStack(Items.IRON_INGOT, 2);
        captured.getOrCreateTag().putString("runtime", "preserved");

        assertTrue(OutputAccounting.hasCapturedExpectedProduction(delegate, List.of(captured)));
        assertEquals(2, captured.getCount());
        assertEquals("preserved", captured.getTag().getString("runtime"));
    }

    @Test
    void capturedProductionCountDoesNotOverflowWhenCaptureExceedsTheTarget() {
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.getExpectedProduction()).thenReturn(
                new IBatchDelegate.ExpectedProduction(new ItemStack(Items.IRON_INGOT), Integer.MAX_VALUE));

        assertTrue(OutputAccounting.hasCapturedExpectedProduction(delegate, List.of(
                new ItemStack(Items.IRON_INGOT, Integer.MAX_VALUE - 1),
                new ItemStack(Items.IRON_INGOT, 2))));
    }

    @Test
    void settlesPrimaryAndSecondaryPortsIndependently() {
        OutputContract contract = new OutputContract(List.of(
                port("product", 0, Items.IRON_INGOT, 4,
                        InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.SLOT),
                port("slag", 1, Items.FLINT, 1,
                        InputBufferPlan.OutputPort.Kind.SECONDARY, OutputContract.Source.WORLD)));

        OutputAccounting.Settlement settlement = OutputAccounting.assess(contract, 2, List.of(
                output("product", OutputContract.Source.SLOT, Items.IRON_INGOT, 8),
                output("slag", OutputContract.Source.WORLD, Items.FLINT, 2)));

        assertTrue(settlement.complete());
        assertEquals(8, settlement.ports().get(0).actual());
        assertEquals(2, settlement.ports().get(1).actual());
    }

    @Test
    void rejectsShortOrWrongSourceOutputWithoutCrossPortSubstitution() {
        OutputContract contract = new OutputContract(List.of(
                port("product", 0, Items.IRON_INGOT, 4,
                        InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.SLOT),
                port("slag", 1, Items.FLINT, 1,
                        InputBufferPlan.OutputPort.Kind.SECONDARY, OutputContract.Source.WORLD)));

        OutputAccounting.Settlement settlement = OutputAccounting.assess(contract, 2, List.of(
                output("product", OutputContract.Source.SLOT, Items.IRON_INGOT, 7),
                output("slag", OutputContract.Source.SLOT, Items.FLINT, 2)));

        assertFalse(settlement.complete());
        assertFalse(settlement.ports().get(0).complete());
        assertFalse(settlement.ports().get(1).matchingOutput());
        assertEquals(List.of("slag"), settlement.unexpected().stream()
                .map(OutputAccounting.CollectedOutput::portId).toList());
    }

    @Test
    void keepsUnknownPortOutputUnexpectedAndAllowsRuntimeNbtOnTaglessDeclaration() {
        OutputContract contract = new OutputContract(List.of(
                port("product", null, Items.IRON_INGOT, 1,
                        InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.VIRTUAL)));
        ItemStack runtimeVariant = new ItemStack(Items.IRON_INGOT);
        CompoundTag tag = new CompoundTag();
        tag.putInt("runtime_value", 7);
        runtimeVariant.setTag(tag);

        OutputAccounting.Settlement settlement = OutputAccounting.assess(contract, 1, List.of(
                new OutputAccounting.CollectedOutput("product", OutputContract.Source.VIRTUAL,
                        runtimeVariant),
                output("unregistered", OutputContract.Source.WORLD, Items.FLINT, 1)));

        assertFalse(settlement.complete());
        assertTrue(settlement.ports().get(0).complete());
        assertEquals(1, settlement.unexpected().size());
    }

    @Test
    void capturedWorldOutputIsNotDuplicatedByRecipeFallback() {
        ItemStack expected = new ItemStack(Items.IRON_INGOT);

        List<ItemStack> merged = OutputAccounting.mergeLegacyOutputs(
                List.of(new ItemStack(Items.IRON_INGOT)),
                List.of(new ItemStack(Items.IRON_INGOT)),
                expected);

        assertEquals(1, merged.size());
        assertEquals(1, merged.get(0).getCount());
        assertTrue(merged.get(0).is(Items.IRON_INGOT));
    }

    @Test
    void capturedWorldOutputKeepsFallbackOutputBeyondCapturedQuantity() {
        ItemStack expected = new ItemStack(Items.IRON_INGOT);

        List<ItemStack> merged = OutputAccounting.mergeLegacyOutputs(
                List.of(new ItemStack(Items.IRON_INGOT)),
                List.of(new ItemStack(Items.IRON_INGOT, 2),
                        new ItemStack(Items.GOLD_INGOT)),
                expected);

        assertEquals(3, merged.size());
        assertEquals(1, merged.get(0).getCount());
        assertEquals(1, merged.get(1).getCount());
        assertTrue(merged.get(1).is(Items.IRON_INGOT));
        assertTrue(merged.get(2).is(Items.GOLD_INGOT));
    }

    @Test
    void missingWorldCaptureKeepsRecipeFallback() {
        List<ItemStack> merged = OutputAccounting.mergeLegacyOutputs(
                List.of(),
                List.of(new ItemStack(Items.IRON_INGOT)),
                new ItemStack(Items.IRON_INGOT));

        assertEquals(1, merged.size());
        assertTrue(merged.get(0).is(Items.IRON_INGOT));
    }

    private static OutputContract.Port port(String id, Integer physicalPort, Item item,
                                            int perOperation, InputBufferPlan.OutputPort.Kind kind,
                                            OutputContract.Source source) {
        return new OutputContract.Port(id, physicalPort, new ItemStack(item), perOperation, kind, source);
    }

    private static OutputAccounting.CollectedOutput output(String portId, OutputContract.Source source,
                                                            Item item, int count) {
        return new OutputAccounting.CollectedOutput(portId, source, new ItemStack(item, count));
    }
}
