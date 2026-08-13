package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftProgressRuntimeTest extends BootstrapTest {

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

        assertEquals(0, runtime.completedOperations());
        assertEquals(1, runtime.totalOperations());
        assertEquals(1, runtime.runningOperations());

        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        assertEquals(1, runtime.completedOperations());
        assertEquals(0, runtime.runningOperations());
    }

    @Test
    void failedAndStoppedRuntimeReportsDetailAndDrainingWithoutCompleting() {
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.FAILED, "machine jammed");
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                new NodeId(5), "test:failing", delegate, null, null);

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
    void completionFailureKeepsOutputShortageDetail() {
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.DONE, "");
        NodeId node = new NodeId(6);
        CraftNodeRuntime runtime = new CraftNodeRuntime(node, "test:shortage", delegate, null, null);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(new OutputDeclaration(
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
    void worldCaptureRuntimeDoesNotTrustDoneBeforePhysicalOutputArrives() {
        NodeId node = new NodeId(7);
        StubDelegate delegate = new StubDelegate(IBatchDelegate.CraftPhase.DONE, "");
        OperationExecutionKernel kernel = new OperationExecutionKernel(
                new OperationResourceCoordinator(new MachineLeaseRegistry(),
                        new CaptureLeaseRegistry(), new OperationBudget(1, 2)));
        OperationExecutionKernel.Session session = kernel.tryPrepare(
                UUID.randomUUID(), node, 0, new OperationBudget(1, 2),
                new MachineLeaseRegistry.MachineKey(new ResourceLocation("minecraft", "overworld"),
                        BlockPos.ZERO, "test"),
                new OperationResourceCoordinator.CaptureRequest(
                        new ResourceLocation("minecraft", "overworld"),
                        new AABB(0, 0, 0, 1, 1, 1), new ItemStack(Items.DIAMOND)));
        assertTrue(session.commit(() -> true));
        assertTrue(session.tryStart(() -> true));
        CraftNodeRuntime runtime = new CraftNodeRuntime(node, "test:capture", delegate,
                null, null, session);

        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        session.close();
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
        OperationExecutionKernel.Session session = kernel.tryPrepare(
                UUID.randomUUID(), node, 0, new OperationBudget(1, 2),
                new MachineLeaseRegistry.MachineKey(new ResourceLocation("minecraft", "overworld"),
                        BlockPos.ZERO, "test"),
                new OperationResourceCoordinator.CaptureRequest(
                        new ResourceLocation("minecraft", "overworld"),
                        new AABB(0, 0, 0, 1, 1, 1), new ItemStack(Items.DIAMOND)));
        assertTrue(session.commit(() -> true));
        assertTrue(session.tryStart(() -> true));
        CraftNodeRuntime runtime = new CraftNodeRuntime(node, "test:slot-output", delegate,
                null, null, session);

        assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe());
        session.close();
    }

    @Test
    void ownedCapturedOutputEndsWorldCaptureWaitEvenBeforeDeclarationProbeMatches() {
        assertEquals(true, CraftNodeRuntime.shouldSucceed(
                IBatchDelegate.CraftPhase.WORKING, true, true, false));
        assertEquals(false, CraftNodeRuntime.shouldSucceed(
                IBatchDelegate.CraftPhase.DONE, true, false, false));
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
        public boolean tryStartSingleCraft(ServerPlayer player) {
            return true;
        }

        @Override
        public boolean isCraftComplete(ServerLevel level) {
            return phase == CraftPhase.DONE;
        }

        @Override
        public CraftObservation observeCraft(ServerLevel level) {
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
        public void onBatchFinished(ServerPlayer player) { }

        @Override
        public BlockPos getMachinePos() {
            return BlockPos.ZERO;
        }
    }
}
