package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstantBatchDelegateTest extends BootstrapTest {

    @Test
    void sharesBoundedFlatGraphAndParallelSizing() {
        TestDelegate delegate = new TestDelegate(8);

        assertEquals(8, delegate.prepareFlatBatch(100));
        assertEquals(8, delegate.currentExecutions());
        assertEquals(8, delegate.preferredParallelBatchSize(100, 10));

        delegate.prepareGraphBatch(100);
        assertEquals(8, delegate.currentExecutions());
    }

    @Test
    void disabledModeFallsBackToSingleExecution() {
        TestDelegate delegate = new TestDelegate(8);
        delegate.enabled = false;

        assertEquals(1, delegate.prepareFlatBatch(100));
        delegate.prepareGraphBatch(100);
        assertEquals(1, delegate.currentExecutions());
        assertEquals(1, delegate.preferredParallelBatchSize(100, 4));
    }

    @Test
    void scaledCopiesSaturateAndResetReturnsToSingleExecution() {
        TestDelegate delegate = new TestDelegate(64);
        delegate.prepareFlatBatch(64);

        ItemStack scaled = delegate.scale(new ItemStack(Items.STONE, 8), 64);
        assertEquals(512, scaled.getCount());
        assertEquals(Integer.MAX_VALUE,
                delegate.scaledCountForTest(Integer.MAX_VALUE, 2));

        delegate.resetInstantBatch();
        assertEquals(1, delegate.currentExecutions());
    }

    private static final class TestDelegate extends InstantBatchDelegate {
        private final int capacity;
        private boolean enabled = true;

        private TestDelegate(int capacity) {
            this.capacity = capacity;
        }

        @Override
        protected boolean supportsInstantBatch() {
            return enabled;
        }

        @Override
        protected int instantBatchCapacity() {
            return capacity;
        }

        private int currentExecutions() {
            return preparedExecutions();
        }

        private ItemStack scale(ItemStack stack, int executions) {
            return scaledCopy(stack, executions);
        }

        private int scaledCountForTest(int count, int executions) {
            return scaledCount(count, executions);
        }

        @Override
        public BlockPos getMachinePos() {
            return BlockPos.ZERO;
        }

        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                       ResourceLocation dim, BlockPos pos) {
            return true;
        }

        @Override
        protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity blockEntity) {
            return true;
        }

        @Override
        public ItemStack collectResult(ServerPlayer player) {
            return ItemStack.EMPTY;
        }

        @Override
        public void onBatchFinished(ServerPlayer player) {
        }
    }
}
