package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutputReceptionLifecycleTest extends BootstrapTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startCreatesPrivateHoldingWithoutANativeNetworkAndKeepsActualOutput(boolean accepted) {
        AbstractBatchDelegate delegate = mock(AbstractBatchDelegate.class, CALLS_REAL_METHODS);
        ServerPlayer player = mock(ServerPlayer.class);
        var iron = TaskOutputReception.resource(new ItemStack(Items.IRON_INGOT));
        when(delegate.supportsOutputReception()).thenReturn(true);
        doAnswer(call -> {
            assertNotNull(delegate.outputReception());
            var batch = delegate.outputReception().prepare(List.of(new TaskOutputReception.Demand("out", iron, 2, 2)));
            batch.collect(iron, 2);
            if (accepted) batch.activate();
            return accepted;
        }).when(delegate).startSingleOperation(player);
        assertEquals(accepted, delegate.startOperation(OperationStartContext.delegateExtracted(
                player, new ExtractionLedger())));
        try (var session = delegate.outputReception()) {
            assertEquals(!accepted, session.closed());
            assertEquals(2, delegate.drainReceivedOutputs().get(0).getCount());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
        }
    }

    @Test
    void serialSettlementCollectsPrivateResourcesOnceAndKeepsActualSurplus() throws Exception {
        var iron = TaskOutputReception.resource(new ItemStack(Items.IRON_INGOT));
        try (var session = TaskOutputReception.open()) {
            var batch = session.prepare(List.of(new TaskOutputReception.Demand("out", iron, 8, 8)));
            batch.activate();
            batch.collect(iron, 5);
            batch.collect(iron, 5);
            IBatchDelegate delegate = mock(IBatchDelegate.class, CALLS_REAL_METHODS);
            when(delegate.outputReception()).thenReturn(session);
            when(delegate.outputContract()).thenReturn(new OutputContract(List.of(
                    new OutputContract.Port("out", 0, new ItemStack(Items.IRON_INGOT), 8,
                            OutputContract.Kind.PRIMARY, OutputContract.Source.SLOT))));
            doReturn(List.of()).when(delegate).collectAllResults(any());
            AsyncCraftChain chain = mock(AsyncCraftChain.class, CALLS_REAL_METHODS);
            var collect = AsyncCraftChain.class.getDeclaredMethod("collectOutputsForSettlement",
                    IBatchDelegate.class, ServerPlayer.class, List.class);
            collect.setAccessible(true);
            Object result = collect.invoke(chain, delegate, mock(ServerPlayer.class), List.of());
            var stacks = result.getClass().getDeclaredMethod("stacks");
            var settlement = result.getClass().getDeclaredMethod("settlement");
            stacks.setAccessible(true);
            settlement.setAccessible(true);
            List<?> actual = (List<?>) stacks.invoke(result);
            assertEquals(10, ((ItemStack) actual.get(0)).getCount());
            assertTrue(((OutputAccounting.Settlement) settlement.invoke(result)).complete());
            assertTrue(session.closed());
            assertTrue(session.drain().isEmpty());
            assertTrue(((List<?>) stacks.invoke(collect.invoke(chain, delegate,
                    mock(ServerPlayer.class), List.of()))).isEmpty());
        }
    }

    @Test
    void deliveredAndObservedReportsNeverCreatePrivateGraphInventory() {
        IBatchDelegate delegate = mock(IBatchDelegate.class, CALLS_REAL_METHODS);
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        CollectedOutput report = CollectedOutput.primary(TaskOutputReception.resource(iron), 8,
                new ResourceLocation("test", "out"));
        when(delegate.drainReportedResources()).thenReturn(List.of(report), List.of());
        when(delegate.drainObservedProduction()).thenReturn(List.of(report), List.of());
        CraftNodeRuntime runtime = new CraftNodeRuntime(new NodeId(0), "test", delegate, null, null);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(OutputDeclaration.item(
                new OutputPortId(new NodeId(0), 0), MaterialKey.of(iron), 8, OutputKind.PRIMARY))));
        assertTrue(runtime.drainIncrementalOutputs().isEmpty());
        assertFalse(runtime.outputsComplete());
        assertTrue(runtime.consumeProgressSignal());
        assertEquals(List.of(report), runtime.drainDeliveredReports());
        assertTrue(runtime.drainDeliveredReports().isEmpty());
        assertTrue(runtime.drainIncrementalOutputs().isEmpty());
        assertFalse(runtime.consumeProgressSignal());
    }
}
