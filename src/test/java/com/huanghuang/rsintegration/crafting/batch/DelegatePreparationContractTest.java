package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegatePreparationContractTest {

    @Test
    void legacyValidationSuccessMapsToReady() {
        IBatchDelegate delegate = new StubDelegate(true);
        assertEquals(IBatchDelegate.PreparationState.READY,
                delegate.prepare(null, new ResourceLocation("test", "recipe"), null, BlockPos.ZERO).state());
    }

    @Test
    void legacyValidationFailureRemainsRetryable() {
        IBatchDelegate delegate = new StubDelegate(false);
        IBatchDelegate.PreparationResult result = delegate.prepare(
                null, new ResourceLocation("test", "recipe"), null, BlockPos.ZERO);
        assertEquals(IBatchDelegate.PreparationState.RETRY, result.state());
    }

    @Test
    void delegateCanDeclarePermanentContractFailure() {
        IBatchDelegate delegate = new StubDelegate(false) {
            @Override
            public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                             ResourceLocation dim, BlockPos pos) {
                return PreparationResult.fatal("recipe type unsupported");
            }
        };
        IBatchDelegate.PreparationResult result = delegate.prepare(
                null, new ResourceLocation("test", "recipe"), null, BlockPos.ZERO);
        assertEquals(IBatchDelegate.PreparationState.FATAL, result.state());
        assertEquals("recipe type unsupported", result.detail());
    }

    @Test
    void fatalPreparationCanCarryLocalizedPlayerMessage() {
        Component message = Component.translatable("rsi.wr.error.insufficient_wissen", "0", "1,000");
        IBatchDelegate.PreparationResult result =
                IBatchDelegate.PreparationResult.fatal("insufficient Wissen", message);

        assertEquals(IBatchDelegate.PreparationState.FATAL, result.state());
        assertEquals(message, result.userMessage());
    }

    @Test
    void operationLeaseUsesDelegatesPhysicalMachinePosition() {
        IBatchDelegate delegate = new StubDelegate(true);
        assertEquals(BlockPos.ZERO,
                delegate.getOperationMachinePos(new BlockPos(8, 64, 8)));
    }

    @Test
    void operationLeaseFallsBackToBindingForVirtualDelegate() {
        BlockPos binding = new BlockPos(8, 64, 8);
        IBatchDelegate delegate = new StubDelegate(true) {
            @Override public BlockPos getMachinePos() { return null; }
        };
        assertEquals(binding, delegate.getOperationMachinePos(binding));
    }

    /**
     * A repeat terminal callback must not reach {@code clearMachineState}.
     * It would be actively harmful rather than merely redundant: cleanup ends in
     * {@code resetState()}, which clears {@code usingSharedLedger}, so a second
     * pass would refund physical machine items that the chain ledger already
     * covered — a dupe.
     */
    @Test
    void repeatOnBatchFailedIsIgnored() {
        CountingDelegate delegate = new CountingDelegate();

        // markTerminalCleanup is the gate onBatchFailed consults; assert it
        // directly so the test needs no Minecraft/logger bootstrap.
        assertTrue(delegate.claimTerminal(), "first settle must win");
        assertFalse(delegate.claimTerminal(), "second settle must be rejected");
        assertFalse(delegate.claimTerminal(), "third settle must be rejected");
    }

    /** Machine-less delegate: onBatchFailed routes to clearMissingMachineState. */
    private static class CountingDelegate extends AbstractBatchDelegate {
        int missingStateCleanups;

        /** Exposes the protected exactly-once gate for assertion. */
        boolean claimTerminal() {
            return markTerminalCleanup();
        }

        @Override
        protected void clearMissingMachineState(ServerPlayer player) {
            missingStateCleanups++;
        }

        @Override
        protected void clearMachineState(net.minecraft.world.level.block.entity.BlockEntity be,
                                         ServerPlayer player) {
            missingStateCleanups++;
        }

        @Override
        protected boolean isMachineCraftFinished(
                ServerLevel level, net.minecraft.world.level.block.entity.BlockEntity be) {
            return false;
        }

        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                      ResourceLocation dim, BlockPos pos) {
            return true;
        }

        @Override public boolean tryStartSingleCraft(ServerPlayer player) { return false; }
        @Override public ItemStack collectResult(ServerPlayer player) { return ItemStack.EMPTY; }
        @Override public void onBatchFinished(ServerPlayer player) {}
        // Non-null so onBatchFailed does not short-circuit on the virtual-delegate path.
        @Override public BlockPos getMachinePos() { return BlockPos.ZERO; }
    }

    private static class StubDelegate implements IBatchDelegate {
        private final boolean valid;

        StubDelegate(boolean valid) {
            this.valid = valid;
        }

        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                       ResourceLocation dim, BlockPos pos) {
            return valid;
        }

        @Override public boolean tryStartSingleCraft(ServerPlayer player) { return false; }
        @Override public boolean isCraftComplete(ServerLevel level) { return false; }
        @Override public ItemStack collectResult(ServerPlayer player) { return ItemStack.EMPTY; }
        @Override public void onBatchFailed(ServerPlayer player, String reason) {}
        @Override public void onBatchFinished(ServerPlayer player) {}
        @Override public BlockPos getMachinePos() { return BlockPos.ZERO; }
    }
}
