package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;

class CookingPotInputBufferTest extends BootstrapTest {

    @Test
    void capacityUsesTheTightestIngredientContainerOrOutputSlot() {
        List<InputBufferContract.InputSlot> inputs = List.of(
                slot("legacy:material:0", 0, 1, 64),
                slot("legacy:material:1", 1, 2, 32),
                slot("legacy:material:2", 7, 1, 12));

        assertEquals(12, CookingPotBatchDelegate.bufferedOperationCapacity(
                64, inputs, 1, 64));
        assertEquals(7, CookingPotBatchDelegate.bufferedOperationCapacity(
                64, inputs, 2, 14));
    }

    @Test
    void invalidPerOperationCountsDisableBuffering() {
        List<InputBufferContract.InputSlot> inputs = List.of(
                slot("legacy:material:0", 0, 0, 64));

        assertEquals(0, CookingPotBatchDelegate.bufferedOperationCapacity(
                64, inputs, 1, 64));
    }

    @Test
    void copperCupConversionUsesDoubledContainerAndOutputCounts() {
        List<InputBufferContract.InputSlot> inputs = List.of(
                slot("legacy:material:0", 0, 1, 64),
                slot("legacy:material:1", 5, 2, 64));

        assertEquals(32, CookingPotBatchDelegate.bufferedOperationCapacity(
                64, inputs, 2, 64));
        assertEquals(12, CookingPotBatchDelegate.bufferedOperationCapacity(
                12, inputs, 2, 64));
    }

    @Test
    void structuredSettlementCollectsTheRealOutputForCookingAndCopperPots() {
        for (CookingPotBatchDelegate delegate : List.of(
                spy(new CookingPotBatchDelegate()), spy(new MinersDelightCopperPotBatchDelegate()))) {
            boolean copperPot = delegate instanceof MinersDelightCopperPotBatchDelegate;
            String portId = copperPot
                    ? "miners_delight:copper_pot:output" : "farmersdelight:cooking_pot:output";
            OutputContract contract = new OutputContract(List.of(new OutputContract.Port(
                    portId, copperPot ? 6 : 8, new ItemStack(Items.APPLE), 1,
                    InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.SLOT)));
            ServerPlayer player = mock(ServerPlayer.class);
            doReturn(contract).when(delegate).outputContract();
            doReturn(new ItemStack(Items.APPLE, 6)).when(delegate).collectResult(player);

            List<OutputAccounting.CollectedOutput> outputs = delegate.collectStructuredResults(player);
            assertEquals(1, outputs.size());
            assertEquals(portId, outputs.get(0).portId());
            assertEquals(6, outputs.get(0).stack().getCount());
            assertTrue(OutputAccounting.assess(contract, 6, outputs).complete());
        }
    }

    private static InputBufferContract.InputSlot slot(String id, int slot,
                                                       int perOperation, int capacity) {
        return new InputBufferContract.InputSlot(id, slot, new ItemStack(Items.APPLE),
                perOperation, false, capacity);
    }
}
