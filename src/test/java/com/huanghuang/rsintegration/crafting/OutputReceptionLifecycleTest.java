package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.ProductionEvidence;
import com.huanghuang.rsintegration.crafting.batch.OperationLifecycle;
import com.huanghuang.rsintegration.crafting.batch.MachineOperation;
import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
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
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutputReceptionLifecycleTest extends BootstrapTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startCreatesPrivateHoldingWithoutANativeNetworkAndKeepsActualOutput(boolean accepted) {
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        MachineOperation machine = mock(MachineOperation.class);
        when(delegate.machineOperation()).thenReturn(machine);
        var context = OperationStartContext.delegateExtracted(mock(ServerPlayer.class), ExtractionLedger.external());
        try (var session = TaskOutputReception.open()) {
            var operation = new OperationLifecycle(delegate, session);
            var writer = new AtomicReference<TaskOutputReception.Writer>();
            doAnswer(call -> { writer.set(call.getArgument(0)); return null; }).when(machine).bindProduction(any());
            when(machine.start(context)).thenAnswer(call -> {
                writer.get().hold(new ItemStack(Items.IRON_INGOT, 2));
                return accepted;
            });
            assertEquals(accepted, operation.start(context));
            assertEquals(!accepted, session.closed());
            assertEquals(2, operation.drainHeld().get(0).getCount());
            assertTrue(operation.drainHeld().isEmpty());
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
        when(delegate.machineOperation()).thenReturn(mock(MachineOperation.class));
            when(delegate.outputContract()).thenReturn(new OutputContract(List.of(
                    new OutputContract.Port("out", 0, new ItemStack(Items.IRON_INGOT), 8,
                            OutputContract.Kind.PRIMARY, OutputContract.Source.SLOT))));
            doReturn((OperationOutputSource) p -> OperationOutputSource.Handoff.physical(List.of(), null)).when(delegate).outputSource();
            var result = new OperationLifecycle(delegate, session).collect(mock(ServerPlayer.class), 1, List::of);
            assertEquals(10, result.stacks().get(0).getCount());
            assertTrue(result.settlement().complete());
            assertTrue(session.closed());
            assertTrue(session.drain().isEmpty());
        }
    }

    @Test
    void finalAuditCountsEarlierDeliveriesButNeverPublishesThemTwice() {
        var item = new ItemStack(Items.IRON_INGOT);
        var contract = new OutputContract(List.of(new OutputContract.Port("out", 0, item, 3,
                OutputContract.Kind.PRIMARY, OutputContract.Source.SLOT)));
        for (boolean useSlot : List.of(true, false)) {
            IBatchDelegate prepared = mock(IBatchDelegate.class);
            var machine = mock(MachineOperation.class);
            when(prepared.machineOperation()).thenReturn(machine);
            when(prepared.outputContract()).thenReturn(contract);
            try (var session = TaskOutputReception.open()) {
                var operation = new OperationLifecycle(prepared, session);
                session.writer().hold(item.copyWithCount(2));
                assertEquals(2, operation.drainHeld().get(0).getCount());
                if (useSlot) {
                    when(prepared.outputSource()).thenReturn(OperationOutputSource.single(player -> item.copy(), contract));
                } else {
                    when(prepared.outputSource()).thenReturn(player -> {
                        session.writer().hold(item.copy());
                        return OperationOutputSource.Handoff.physical(List.of(), contract);
                    });
                }
                var published = new ArrayList<ItemStack>();
                var settled = new AtomicInteger();
                assertTrue(operation.finish(null, 1, List::of, published::addAll, null, settled::incrementAndGet));
                assertEquals(1, published.stream().mapToInt(ItemStack::getCount).sum());
                assertEquals(1, settled.get());
                verify(machine).cleanup(null);
            }
        }
    }

    @Test
    void captureFailureAfterSlotExtractionKeepsActualStackAndDoesNotReopenRefunds() {
        var delegate = mock(IBatchDelegate.class);
        var machine = mock(MachineOperation.class);
        when(delegate.machineOperation()).thenReturn(machine);
        when(delegate.outputSource()).thenReturn(OperationOutputSource.single(p -> new ItemStack(Items.DIAMOND, 3), null));
        var operation = new OperationLifecycle(delegate);
        assertThrows(IllegalStateException.class, () -> operation.collect(null, 1, () -> {
            throw new IllegalStateException("捕获区不可用");
        }));
        assertEquals(3, operation.retireHolding().get(0).getCount());
        operation.recover(null, "取消");
        operation.recover(null, "停服");
        verify(machine).cleanup(null);
        verify(machine, never()).recover(any(), anyString());
        assertTrue(operation.retireHolding().isEmpty());
    }

    @Test
    void deliveredAndObservedReportsNeverCreatePrivateGraphInventory() {
        IBatchDelegate delegate = mock(IBatchDelegate.class, withSettings().extraInterfaces(ProductionEvidence.class).defaultAnswer(CALLS_REAL_METHODS));
        when(delegate.machineOperation()).thenReturn(mock(MachineOperation.class));
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        CollectedOutput report = CollectedOutput.primary(TaskOutputReception.resource(iron), 8,
                new ResourceLocation("test", "out"));
        when(ProductionEvidence.from(delegate).takeDelivered()).thenReturn(List.of(report), List.of());
        when(ProductionEvidence.from(delegate).takeObserved()).thenReturn(List.of(report), List.of());
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
