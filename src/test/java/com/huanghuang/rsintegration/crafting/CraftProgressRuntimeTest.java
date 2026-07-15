package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftProgressRuntimeTest extends BootstrapTest {

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

    private static final class StubDelegate implements IBatchDelegate {
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
