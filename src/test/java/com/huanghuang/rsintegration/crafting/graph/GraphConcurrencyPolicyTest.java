package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphConcurrencyPolicyTest {

    @Test
    void disabledModOverridesDelegateCapability() {
        assertTrue(GraphConcurrencyPolicy.isModDisabled("malum", List.of(" MALUM ", "goety")));
        assertFalse(GraphConcurrencyPolicy.isModDisabled("embers", List.of("malum")));
    }

    @Test
    void unknownOrUnoptedDelegateIsExclusive() {
        assertTrue(GraphConcurrencyPolicy.isExclusive("test", null));
        assertTrue(GraphConcurrencyPolicy.isExclusive("test", new FakeDelegate(false)));
    }

    @Test
    void optedDelegateCanRunConcurrentlyWhenNotDisabled() {
        assertFalse(GraphConcurrencyPolicy.isExclusive("test", new FakeDelegate(true)));
    }

    private static final class FakeDelegate implements IBatchDelegate {
        private final boolean concurrent;

        private FakeDelegate(boolean concurrent) { this.concurrent = concurrent; }
        @Override public boolean supportsConcurrentNodeExecution() { return concurrent; }
        @Override public boolean validateAndInit(ServerPlayer p, ResourceLocation r, ResourceLocation d, BlockPos pos) { return false; }
        @Override public boolean tryStartSingleCraft(ServerPlayer p) { return false; }
        @Override public boolean isCraftComplete(ServerLevel level) { return false; }
        @Override public ItemStack collectResult(ServerPlayer p) { return ItemStack.EMPTY; }
        @Override public void onBatchFailed(ServerPlayer p, String reason) { }
        @Override public void onBatchFinished(ServerPlayer p) { }
        @Override public BlockPos getMachinePos() { return BlockPos.ZERO; }
    }
}
