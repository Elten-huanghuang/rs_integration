package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedDelegateOwnershipTest extends BootstrapTest {

    private static final ResourceLocation RECIPE = new ResourceLocation("test", "prepared_delegate");

    @Test
    void rejectedValidationReleasesPreparationResources() {
        TrackingDelegate delegate = new TrackingDelegate(false, false);

        boolean valid = AsyncCraftChain.validatePreparedDelegate(
                delegate, null, RECIPE, null, BlockPos.ZERO);

        assertTrue(!valid);
        assertEquals(1, delegate.preparationReleases);
    }

    @Test
    void validationExceptionReleasesPreparationResources() {
        TrackingDelegate delegate = new TrackingDelegate(false, true);

        assertThrows(IllegalStateException.class, () ->
                AsyncCraftChain.validatePreparedDelegate(
                        delegate, null, RECIPE, null, BlockPos.ZERO));

        assertEquals(1, delegate.preparationReleases);
    }

    @Test
    void acceptedValidationTransfersPreparationOwnership() {
        TrackingDelegate delegate = new TrackingDelegate(true, false);

        boolean valid = AsyncCraftChain.validatePreparedDelegate(
                delegate, null, RECIPE, null, BlockPos.ZERO);

        assertTrue(valid);
        assertEquals(0, delegate.preparationReleases);
    }

    @Test
    void preparedValidationUsesStructuredPreparationResult() {
        TrackingDelegate delegate = new TrackingDelegate(false, true) {
            @Override
            public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                             ResourceLocation dimension, BlockPos position) {
                return PreparationResult.ready();
            }
        };

        assertTrue(AsyncCraftChain.validatePreparedDelegate(
                delegate, null, RECIPE, null, BlockPos.ZERO));
        assertEquals(0, delegate.preparationReleases);
    }

    private static class TrackingDelegate implements IBatchDelegate {
        private final boolean validationResult;
        private final boolean throwDuringValidation;
        private int preparationReleases;

        private TrackingDelegate(boolean validationResult, boolean throwDuringValidation) {
            this.validationResult = validationResult;
            this.throwDuringValidation = throwDuringValidation;
        }

        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                       ResourceLocation dim, BlockPos pos) {
            if (throwDuringValidation) throw new IllegalStateException("validation failed");
            return validationResult;
        }

        @Override public boolean tryStartSingleCraft(ServerPlayer player) { return false; }
        @Override public boolean isCraftComplete(ServerLevel level) { return false; }
        @Override public ItemStack collectResult(ServerPlayer player) { return ItemStack.EMPTY; }
        @Override public void releasePreparationResources() { preparationReleases++; }
        @Override public void onBatchFailed(ServerPlayer player, String reason) {}
        @Override public void onBatchFinished(ServerPlayer player) {}
        @Override public BlockPos getMachinePos() { return BlockPos.ZERO; }
    }
}
