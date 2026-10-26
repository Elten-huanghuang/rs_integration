package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CraftProgressRuntimeTest extends BootstrapTest {
    private ServerPlayer player;
    private ServerLevel level;

    @BeforeEach
    void createWorldContext() {
        MinecraftServer server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);
        player = mock(ServerPlayer.class);
        when(player.getServer()).thenReturn(server);
        when(player.serverLevel()).thenReturn(level);
    }

    @BeforeAll
    static void loadDefaultServerConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @Test
    void ordinaryRuntimeReportsOneRunningOperationThenCompletion() {
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.DONE, "");
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                new NodeId(4), "test:ordinary", delegate, null, null);
        runtime.setChainContext(new ArrayList<>(), player);

        assertEquals(0, runtime.completedOperations());
        assertEquals(1, runtime.totalOperations());
        assertEquals(1, runtime.runningOperations());

        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        assertSame(level, delegate.observedLevel);
        assertEquals(1, runtime.completedOperations());
        assertEquals(0, runtime.runningOperations());
    }

    @Test
    void failedAndStoppedRuntimeReportsDetailAndDrainingWithoutCompleting() {
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.FAILED, "machine jammed");
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                new NodeId(5), "test:failing", delegate, null, null);
        runtime.setChainContext(new ArrayList<>(), player);

        assertEquals(ConcurrentNodeExecutor.Observation.FAILED, runtime.observe());
        assertEquals("machine jammed", runtime.failureReason());
        runtime.stopDispatch();
        runtime.cleanupFailure();

        assertTrue(runtime.isDraining());
        assertEquals(0, runtime.completedOperations());
        assertEquals(0, runtime.runningOperations());
        assertEquals(1, delegate.failureCleanups);
    }

    @Test
    void bufferedRuntimeReportsLogicalOperationsInsteadOfPhysicalWorkerCount() {
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.DONE, "");
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                new NodeId(14), "test:buffered", delegate, null, null);
        runtime.setLogicalOperations(6);
        runtime.setChainContext(new ArrayList<>(), player);

        assertEquals(6, runtime.totalOperations());
        assertEquals(6, runtime.runningOperations());
        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        assertEquals(6, runtime.completedOperations());
        assertEquals(0, runtime.runningOperations());
    }

    @Test
    void missingWorldContextFailsBeforeCallingDelegate() {
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.DONE, "");
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                new NodeId(10), "test:missing-world", delegate, null, null);

        assertEquals(ConcurrentNodeExecutor.Observation.FAILED, runtime.observe());
        assertEquals("graph observation level unavailable", runtime.failureReason());
        assertEquals(0, delegate.observationCalls);
        assertEquals(0, runtime.completedOperations());
    }

    @Test
    void cancellationRecoversReusableMaterialsBeforeMachineCleanup() {
        List<String> calls = new ArrayList<>();
        CraftNodeRuntime.runFailureCleanup(true,
                () -> calls.add("reusable"), () -> calls.add("cleanup"));

        assertEquals(List.of("reusable", "cleanup"), calls);
    }

    @Test
    void catalystRecoveryFailureDoesNotSkipMachineCleanup() {
        List<String> calls = new ArrayList<>();
        CraftNodeRuntime.runFailureCleanup(true,
                () -> { throw new IllegalStateException("recovery failed"); },
                () -> calls.add("cleanup"));

        assertEquals(List.of("cleanup"), calls);
    }

    @Test
    void inFlightInputsRefundOnlyAfterPhysicalMachineCleanup() {
        assertTrue(CraftNodeRuntime.shouldRefundInFlightMaterials(
                OperationExecutionKernel.TerminalClass.IN_FLIGHT, true));
        assertFalse(CraftNodeRuntime.shouldRefundInFlightMaterials(
                OperationExecutionKernel.TerminalClass.IN_FLIGHT, false));
        assertFalse(CraftNodeRuntime.shouldRefundInFlightMaterials(
                OperationExecutionKernel.TerminalClass.SETTLED, true));
    }

    @Test
    void flatInFlightInputsAlsoRequirePhysicalRecovery() {
        assertTrue(AsyncCraftChain.shouldRefundFlatCommitted(
                OperationExecutionKernel.TerminalClass.PRE_START, false));
        assertTrue(AsyncCraftChain.shouldRefundFlatCommitted(
                OperationExecutionKernel.TerminalClass.IN_FLIGHT, true));
        assertFalse(AsyncCraftChain.shouldRefundFlatCommitted(
                OperationExecutionKernel.TerminalClass.IN_FLIGHT, false));
    }

    @Test
    void completionFailureKeepsOutputShortageDetail() {
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.DONE, "");
        NodeId node = new NodeId(6);
        CraftNodeRuntime runtime = new CraftNodeRuntime(node, "test:shortage", delegate, null, null);
        runtime.setChainContext(new ArrayList<>(), player);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(OutputDeclaration.item(
                new OutputPortId(node, 0), MaterialKey.of(new ItemStack(net.minecraft.world.item.Items.DIAMOND)),
                1, OutputKind.PRIMARY))));

        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        String detail = runtime.outputShortageDetail();
        runtime.markCompletionFailed(detail);

        assertTrue(detail.contains("minecraft:diamond"));
        assertTrue(detail.contains("missing=1"));
        assertEquals(detail, runtime.failureReason());
    }

    @Test
    void worldStateMutationDoesNotRequireDisplayOnlyGraphOutput() {
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.DONE, "") {
            @Override
            public boolean publishesDeclaredGraphOutputs() {
                return false;
            }
        };
        NodeId node = new NodeId(9);
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                node, "test:world-state-mutation", delegate, null, null);
        runtime.setChainContext(new ArrayList<>(), player);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(OutputDeclaration.item(
                new OutputPortId(node, 0), MaterialKey.of(new ItemStack(Items.DIAMOND)),
                1, OutputKind.PRIMARY))));

        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        assertTrue(runtime.outputsComplete());
    }

    @Test
    void worldCaptureRuntimeDoesNotTrustDoneBeforePhysicalOutputArrives() {
        NodeId node = new NodeId(7);
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.DONE, "");
        OperationExecutionKernel kernel = new OperationExecutionKernel(
                new OperationResourceCoordinator(new MachineLeaseRegistry(),
                        new CaptureLeaseRegistry(), new OperationBudget(1, 2)));
        try (OperationExecutionKernel.Session session = kernel.tryPrepare(
                UUID.randomUUID(), node, 0, new OperationBudget(1, 2),
                new MachineLeaseRegistry.MachineKey(new ResourceLocation("minecraft", "overworld"),
                        BlockPos.ZERO, "test"),
                new OperationResourceCoordinator.CaptureRequest(
                        new ResourceLocation("minecraft", "overworld"),
                        new AABB(0, 0, 0, 1, 1, 1), new ItemStack(Items.DIAMOND)))) {
            assertNotNull(session);
            assertTrue(session.commit(() -> true));
            assertTrue(session.tryStart(() -> true));
            CraftNodeRuntime runtime = new CraftNodeRuntime(node, "test:capture", delegate,
                    null, null, session);
            runtime.setChainContext(new ArrayList<>(), player);

            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            assertSame(level, delegate.observedLevel);
        }
    }

    @Test
    void slotCollectingRuntimeCanFinishWhileDefensiveWorldCaptureIsEmpty() {
        NodeId node = new NodeId(8);
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.DONE, "") {
            @Override
            public boolean canCollectResultWithoutWorldCapture() {
                return true;
            }
        };
        OperationExecutionKernel kernel = new OperationExecutionKernel(
                new OperationResourceCoordinator(new MachineLeaseRegistry(),
                        new CaptureLeaseRegistry(), new OperationBudget(1, 2)));
        try (OperationExecutionKernel.Session session = kernel.tryPrepare(
                UUID.randomUUID(), node, 0, new OperationBudget(1, 2),
                new MachineLeaseRegistry.MachineKey(new ResourceLocation("minecraft", "overworld"),
                        BlockPos.ZERO, "test"),
                new OperationResourceCoordinator.CaptureRequest(
                        new ResourceLocation("minecraft", "overworld"),
                        new AABB(0, 0, 0, 1, 1, 1), new ItemStack(Items.DIAMOND)))) {
            assertNotNull(session);
            assertTrue(session.commit(() -> true));
            assertTrue(session.tryStart(() -> true));
            CraftNodeRuntime runtime = new CraftNodeRuntime(node, "test:slot-output", delegate,
                    null, null, session);
            runtime.setChainContext(new ArrayList<>(), player);

            assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
            assertSame(level, delegate.observedLevel);
        }
    }

    @Test
    void capturedBatchWithWrongNbtStillReachesOutputValidation() {
        NodeId node = new NodeId(15);
        StubDelegate delegate = bufferedDelegate(2, IBatchDelegate.CraftPhase.WORKING);
        List<ItemStack> captured = new ArrayList<>();
        CaptureSession capture = capture(captured);
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                node, "test:wrong-nbt", delegate, null, null);
        runtime.setChainContext(new ArrayList<>(), player);
        runtime.attachCapture(capture);
        ItemStack declared = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "required");
        declared.setTag(tag);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(OutputDeclaration.item(
                new OutputPortId(node, 0), MaterialKey.of(declared), 2, OutputKind.PRIMARY))));

        captured.add(new ItemStack(Items.DIAMOND));
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        captured.add(new ItemStack(Items.DIAMOND));
        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        assertTrue(runtime.drainIncrementalOutputs().isEmpty());
        assertFalse(runtime.outputsComplete());
        assertTrue(runtime.outputShortageDetail().contains("missing=2"));
    }

    @ParameterizedTest
    @CsvSource({
            "2, WORKING, true",
            "3, WORKING, true",
            "4, WORKING, true",
            "6, DONE, true",
            "3, WORKING, false"
    })
    void bufferedWorldCaptureWaitsForEveryOutputBeforeCleaningTheMachine(
            int operations, IBatchDelegate.CraftPhase phase, boolean declaredOutputs) {
        NodeId node = new NodeId(16);
        StubDelegate delegate = bufferedDelegate(operations, phase);
        List<ItemStack> captured = new ArrayList<>();
        CaptureSession capture = capture(captured);
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                node, "test:buffered-capture", delegate, null, null);
        runtime.setLogicalOperations(operations);
        runtime.setChainContext(new ArrayList<>(), player);
        runtime.attachCapture(capture);
        if (declaredOutputs) {
            runtime.attachOutputs(new NodeOutputAccumulator(List.of(OutputDeclaration.item(
                    new OutputPortId(node, 0), MaterialKey.of(new ItemStack(Items.DIAMOND)),
                    operations, OutputKind.PRIMARY))));
        }

        for (int count = 1; count < operations; count++) {
            captured.add(new ItemStack(Items.DIAMOND));
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            assertEquals(0, runtime.completedOperations());
            assertEquals(0, delegate.finishCleanups);
            assertTrue(runtime.consumeProgressSignal());
            assertEquals(0, runtime.waitTicks());
            verify(capture, never()).drainAndClose();
        }
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertFalse(runtime.consumeProgressSignal());
        assertEquals(1, runtime.waitTicks());

        captured.add(new ItemStack(Items.DIAMOND));
        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        assertEquals(operations, runtime.completedOperations());
        assertEquals(1, delegate.finishCleanups);
        verify(capture).drainAndClose();
        if (declaredOutputs) {
            assertEquals(operations, runtime.drainIncrementalOutputs().stream()
                    .mapToInt(publication -> publication.stack().getCount()).sum());
            assertTrue(runtime.outputsComplete());
            assertTrue(runtime.drainIncrementalOutputs().isEmpty());
        }
    }

    private static StubDelegate bufferedDelegate(int operations, IBatchDelegate.CraftPhase phase) {
        return new StubDelegate(phase, "") {
            @Override
            public ExpectedProduction getExpectedProduction() {
                return new ExpectedProduction(new ItemStack(Items.DIAMOND), operations);
            }
        };
    }

    private static CaptureSession capture(List<ItemStack> captured) {
        CaptureSession capture = mock(CaptureSession.class);
        when(capture.hasCaptured()).thenAnswer(call -> !captured.isEmpty());
        when(capture.snapshot()).thenAnswer(call -> List.copyOf(captured));
        when(capture.drainAndClose()).thenAnswer(call -> List.copyOf(captured));
        return capture;
    }

    @Test
    void flatBatchProgressReportsSettledOperationsBetweenBatches() {
        assertEquals(0, AsyncCraftChain.completedFlatOperations(11, 11));
        assertEquals(8, AsyncCraftChain.completedFlatOperations(11, 3));
        assertEquals(11, AsyncCraftChain.completedFlatOperations(11, 0));
    }

    @Test
    void vanillaExecutionSliceContinuesLargeStepAcrossTicks() {
        var step = genericStep("large", 65);

        var first = AsyncCraftChain.planVanillaBatchSlice(List.of(step), 0, 0, 8);
        var second = AsyncCraftChain.planVanillaBatchSlice(
                List.of(step), first.nextStepIndex(), first.remainingExecutions(), 8);

        assertEquals(8, first.steps().get(0).executions());
        assertEquals(0, first.nextStepIndex());
        assertEquals(57, first.remainingExecutions());
        assertEquals(8, second.steps().get(0).executions());
        assertEquals(49, second.remainingExecutions());
    }

    @Test
    void vanillaExecutionSliceSharesBudgetAcrossSteps() {
        List<CraftingResolver.ResolutionStep> steps = List.of(
                genericStep("first", 3), genericStep("second", 10));

        var slice = AsyncCraftChain.planVanillaBatchSlice(steps, 0, 0, 8);

        assertEquals(List.of(3, 5), slice.steps().stream()
                .map(CraftingResolver.ResolutionStep::executions).toList());
        assertEquals(1, slice.nextStepIndex());
        assertEquals(5, slice.remainingExecutions());
    }

    private static CraftingResolver.ResolutionStep genericStep(String path, int executions) {
        return new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", path), ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"),
                List.of(), List.of(), false, executions);
    }

    private static class StubDelegate implements IBatchDelegate {
        private final CraftPhase phase;
        private final String detail;
        private int failureCleanups;
        private int finishCleanups;
        private ServerLevel observedLevel;
        private int observationCalls;

        private StubDelegate(CraftPhase phase, String detail) {
            this.phase = phase;
            this.detail = detail;
        }

        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                       ResourceLocation dim, BlockPos pos) {
            return true;
        }

        @Override
        public boolean startOperation(OperationStartContext context) {
            return true;
        }

        @Override
        public boolean isCraftComplete(ServerLevel level) {
            return phase == CraftPhase.DONE;
        }

        @Override
        public CraftObservation observeCraft(ServerLevel level) {
            observedLevel = level;
            observationCalls++;
            return new CraftObservation(phase, detail);
        }

        @Override
        public ItemStack collectResult(ServerPlayer player) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack getExpectedOutput() {
            return new ItemStack(Items.DIAMOND);
        }

        @Override
        public void onBatchFailed(ServerPlayer player, String reason) {
            failureCleanups++;
        }

        @Override
        public void onBatchFinished(ServerPlayer player) {
            finishCleanups++;
        }

        @Override
        public BlockPos getMachinePos() {
            return BlockPos.ZERO;
        }
    }
}
