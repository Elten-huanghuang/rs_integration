package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.VirtualExchangeBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.VirtualExchangeRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualExchangeAsyncBatchTest {

    @Test
    void virtualExchangeReceivesEveryRemainingOperationAsOneBatch() {
        TestVirtualExchangeDelegate delegate = new TestVirtualExchangeDelegate();

        int allowed = AsyncCraftChain.genericBatchLimit(delegate, 1024, 32);
        int prepared = delegate.prepareFlatBatch(allowed);

        assertEquals(1024, allowed);
        assertEquals(1024, prepared);
    }

    @Test
    void largeVirtualExchangeNodeUsesFlatBatchExecutionInsteadOfGraphDispatch() {
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "void_exchange"), ModType.FLUID_CONTAINER,
                new ResourceLocation("test", "void_exchange"), List.of(), List.of(),
                false, 1024);

        assertTrue(AsyncCraftChain.requiresFlatExecutionForOversizedNode(
                List.of(step), 8, 32));
    }

    private static final class TestVirtualExchangeDelegate
            extends VirtualExchangeBatchDelegate {
        @Override
        protected boolean validateExchangeAccess(ServerPlayer player,
                                                 ResourceLocation recipeId,
                                                 @Nullable ResourceLocation dim,
                                                 BlockPos pos) {
            return true;
        }

        @Nullable
        @Override
        protected VirtualExchangeRecipe resolveExchangeRecipe(ServerPlayer player,
                                                              ResourceLocation recipeId) {
            return null;
        }
    }
}
