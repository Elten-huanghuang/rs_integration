package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
