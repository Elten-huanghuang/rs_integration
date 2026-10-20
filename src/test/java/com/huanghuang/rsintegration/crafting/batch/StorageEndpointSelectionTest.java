package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageEndpointSelectionTest {

    @Test
    void chainSelectedEndpointCannotBeClearedByMachinePreparation() {
        TestDelegate delegate = new TestDelegate();
        CraftStorageEndpoint selected = () -> null;
        delegate.setStorageEndpoint(selected);
        AtomicBoolean resolved = new AtomicBoolean();

        delegate.resolveIfMissing(() -> {
            resolved.set(true);
            return null;
        });

        assertSame(selected, delegate.endpoint());
        assertFalse(resolved.get());
    }

    @Test
    void missingEndpointCanStillBeResolvedByMachinePreparation() {
        TestDelegate delegate = new TestDelegate();
        CraftStorageEndpoint resolvedEndpoint = () -> null;
        AtomicBoolean resolved = new AtomicBoolean();

        delegate.resolveIfMissing(() -> {
            resolved.set(true);
            return resolvedEndpoint;
        });

        assertTrue(resolved.get());
        assertSame(resolvedEndpoint, delegate.endpoint());
    }

    private static final class TestDelegate extends AbstractBatchDelegate {
        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                       ResourceLocation dimension, BlockPos position) {
            return true;
        }

        @Override
        public ItemStack collectResult(ServerPlayer player) {
            return ItemStack.EMPTY;
        }

        @Override
        public void onBatchFinished(ServerPlayer player) {
        }

        @Override
        public BlockPos getMachinePos() {
            return null;
        }

        void resolveIfMissing(Supplier<CraftStorageEndpoint> resolver) {
            resolveStorageEndpointIfMissing(resolver);
        }

        CraftStorageEndpoint endpoint() {
            return storageEndpoint();
        }
    }
}
