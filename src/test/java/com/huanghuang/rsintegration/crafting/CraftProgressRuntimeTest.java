package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.MachineOperation;
import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
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
    void completedOptionalWorldOutputDoesNotWaitForeverForAnEntityThatMayNeverExist() {
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.machineOperation()).thenReturn(mock(MachineOperation.class));
        when(delegate.machineOperation().observe(level)).thenReturn(
                new MachineOperation.CraftObservation(MachineOperation.CraftPhase.DONE));
        when(delegate.validateExecutionContext(player)).thenReturn(true);
        when(delegate.outputSource()).thenReturn(p -> OperationOutputSource.Handoff.physical(List.of(), null));
        when(delegate.outputContract()).thenReturn(new OutputContract(List.of(
                new OutputContract.Port("random", null, new ItemStack(Items.DIAMOND), 0,
                        OutputContract.Kind.PRIMARY, OutputContract.Source.WORLD, 0, 1,
                        OutputProbability.probabilistic(0.35, 16, "world"), false, null))));
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                new NodeId(0), "test:random_world", delegate, null, null);
        CaptureSession capture = mock(CaptureSession.class);
        when(capture.snapshot()).thenReturn(List.of());
        when(capture.drainAndClose()).thenReturn(List.of());
        runtime.attachCapture(capture);
        runtime.setChainContext(new ArrayList<>(), player);

        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
    }

    @Test
    void graphPublishesTheActualCatalystBeforeFinishCleanupAndOnlyOnce() {
        ItemStack catalyst = new ItemStack(Items.STICK);
        catalyst.getOrCreateTag().putString("owner", "original");
        List<String> calls = new ArrayList<>();
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "") {
            @Override public OperationOutputSource outputSource() {
                return OperationOutputSource.single(player -> new ItemStack(Items.DIAMOND, 4), outputContract());
            }

            @Override
            public List<ItemStack> collectReusableMaterials(ServerPlayer player) {
                calls.add("collect");
                return List.of(catalyst.copy());
            }

            @Override
            public void cleanup(ServerPlayer player) {
                calls.add("finish");
            }

            @Override
            public void releaseReusableMaterials(ServerPlayer player) {
                calls.add("release");
            }
        };
        NodeId node = new NodeId(20);
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                node, "test:catalyst-return", delegate, null, null);
        runtime.setChainContext(new ArrayList<>(), player);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(
                OutputDeclaration.item(new OutputPortId(node, 0),
                        MaterialKey.of(new ItemStack(Items.DIAMOND)), 4, OutputKind.PRIMARY),
                OutputDeclaration.item(new OutputPortId(node, 1), MaterialKey.of(catalyst), 1,
                        OutputKind.CATALYST_RETURN, 1, 1, OutputProbability.guaranteed(),
                        false, new InputPortId(node, 0)))));

        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        var published = runtime.drainIncrementalOutputs();

        assertTrue(runtime.outputsComplete());
        assertEquals(List.of("collect", "finish", "release"), calls);
        assertEquals(2, published.size());
        assertTrue(ItemStack.isSameItemSameTags(catalyst, published.get(1).stack()));
        assertTrue(runtime.drainIncrementalOutputs().isEmpty());
    }

    @Test
    void reusableCollectionFailureStillCleansTheMachineAndPreservesItsActualOutput() {
        NodeId node = new NodeId(18);
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "") {
            @Override public OperationOutputSource outputSource() {
                return OperationOutputSource.single(player -> new ItemStack(Items.DIAMOND), outputContract());
            }

            @Override
            public List<ItemStack> collectReusableMaterials(ServerPlayer player) {
                throw new IllegalStateException("catalyst inventory unavailable");
            }
        };
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                node, "test:catalyst_collection_error", delegate, null, null);
        runtime.setChainContext(new ArrayList<>(), player);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(OutputDeclaration.item(
                new OutputPortId(node, 0), MaterialKey.of(new ItemStack(Items.DIAMOND)),
                1, OutputKind.PRIMARY))));

        assertEquals(ConcurrentNodeExecutor.Observation.FAILED, runtime.observe());
        runtime.cleanupFailure();
        runtime.cleanupFailure();

        // 已取得成品，催化剂访问失败不能转回输入退款路径。
        assertEquals(0, delegate.failureCleanups);
        assertEquals(1, delegate.finishCleanups);
        assertEquals(1, runtime.drainIncrementalOutputs().get(0).stack().getCount());
        assertTrue(runtime.drainIncrementalOutputs().isEmpty());
    }

    @Test
    void failureAfterPartialPublicationRecoversOnlyUnpublishedPhysicalFragmentsOnce() {
        NodeId node = new NodeId(19);
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "") {
            @Override public OperationOutputSource outputSource() {
                return player -> OperationOutputSource.Handoff.physical(List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.DIAMOND, 2)), outputContract());
            }
        };
        List<ItemStack> recovered = new ArrayList<>();
        CraftNodeRuntime runtime = new CraftNodeRuntime(node, "test:partial-publication",
                delegate, null, null);
        runtime.setChainContext(recovered, player);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(OutputDeclaration.item(
                new OutputPortId(node, 0), MaterialKey.of(new ItemStack(Items.DIAMOND)),
                6, OutputKind.PRIMARY))));

        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        runtime.prepareGraphPublications();
        assertEquals(4, runtime.nextGraphPublication().stack().getCount());
        runtime.graphPublicationCompleted();

        runtime.cleanupFailure();
        runtime.cleanupFailure();

        assertFalse(runtime.hasPendingPublications());
        assertEquals(2, recovered.stream().mapToInt(ItemStack::getCount).sum());
        assertTrue(runtime.drainIncrementalOutputs().isEmpty());
        assertEquals(1, delegate.finishCleanups);
        assertEquals(0, delegate.failureCleanups);
    }

    @Test
    void graphStillRejectsAnUnrecoveredCatalyst() {
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "");
        NodeId node = new NodeId(21);
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                node, "test:missing-catalyst", delegate, null, null);
        runtime.setChainContext(new ArrayList<>(), player);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(
                OutputDeclaration.item(new OutputPortId(node, 0),
                        MaterialKey.of(new ItemStack(Items.STICK)), 1,
                        OutputKind.CATALYST_RETURN, 1, 1, OutputProbability.guaranteed(),
                        false, new InputPortId(node, 0)))));

        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        assertTrue(runtime.drainIncrementalOutputs().isEmpty());
        assertFalse(runtime.outputsComplete());
        assertTrue(runtime.outputShortageDetail().contains("catalyst_return"));
    }

    @Test
    void ordinaryRuntimeReportsOneRunningOperationThenCompletion() {
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "");
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
    void observationSlicesCountElapsedWorldTicksInsteadOfOnlyPollingCalls() {
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.WORKING, "");
        CraftNodeRuntime runtime = new CraftNodeRuntime(new NodeId(4), "test:waiting", delegate, null, null);
        runtime.setChainContext(new ArrayList<>(), player);
        when(level.getGameTime()).thenReturn(100L);
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertEquals(1, runtime.waitTicks());

        when(level.getGameTime()).thenReturn(120L);
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertEquals(21, runtime.waitTicks());
        when(level.getGameTime()).thenReturn(120L
                + RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20L);
        assertEquals(ConcurrentNodeExecutor.Observation.FAILED, runtime.observe());
        assertEquals(CraftFailureCode.TIMEOUT, runtime.failureCode());
    }

    @Test
    void failedAndStoppedRuntimeReportsDetailAndDrainingWithoutCompleting() {
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.FAILED, "machine jammed");
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
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "");
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
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "");
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
    void completionFailureKeepsOutputShortageDetail() {
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "");
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
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "") {
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
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "");
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
        StubDelegate delegate = new StubDelegate(MachineOperation.CraftPhase.DONE, "") {
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
        StubDelegate delegate = bufferedDelegate(2, MachineOperation.CraftPhase.WORKING);
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
            "3, WORKING, true",
            "6, DONE, true",
            "3, WORKING, false"
    })
    void bufferedWorldCaptureWaitsForEveryOutputBeforeCleaningTheMachine(
            int operations, MachineOperation.CraftPhase phase, boolean declaredOutputs) {
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

    private static StubDelegate bufferedDelegate(int operations, MachineOperation.CraftPhase phase) {
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

    private static class StubDelegate implements IBatchDelegate, MachineOperation {
        @Override public MachineOperation machineOperation() { return this; }

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
        public boolean start(OperationStartContext context) {
            return true;
        }


        @Override
        public CraftObservation observe(ServerLevel level) {
            observedLevel = level;
            observationCalls++;
            return new CraftObservation(phase, detail);
        }

        @Override public OperationOutputSource outputSource() {
            return OperationOutputSource.single(player -> ItemStack.EMPTY, outputContract());
        }

        @Override
        public ItemStack getExpectedOutput() {
            return new ItemStack(Items.DIAMOND);
        }

        @Override
        public void recover(ServerPlayer player, String reason) {
            failureCleanups++;
        }

        @Override
        public void cleanup(ServerPlayer player) {
            finishCleanups++;
        }

        @Override
        public BlockPos getMachinePos() {
            return BlockPos.ZERO;
        }
    }
}
