package com.huanghuang.rsintegration.network.gui;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;

/** One-shot client snapshot used to construct a Placebo menu from another dimension. */
public final class RemotePlaceboMenuSnapshot {
    private static final long TTL_NANOS = 5_000_000_000L;
    private static final AtomicReference<Snapshot> PENDING = new AtomicReference<>();

    private record Snapshot(BlockPos pos, int blockStateId, CompoundTag blockEntityTag,
                            long expiresAtNanos) {}

    private RemotePlaceboMenuSnapshot() {}

    public static void accept(BlockPos pos, int blockStateId, @Nullable CompoundTag blockEntityTag) {
        if (blockEntityTag == null) {
            PENDING.set(null);
            return;
        }
        PENDING.set(new Snapshot(pos.immutable(), blockStateId, blockEntityTag.copy(),
                System.nanoTime() + TTL_NANOS));
    }

    @Nullable
    public static BlockEntity take(Level level, BlockPos pos) {
        if (!level.isClientSide()) return null;
        Snapshot snapshot = PENDING.get();
        if (snapshot == null) return null;
        if (System.nanoTime() > snapshot.expiresAtNanos()) {
            PENDING.compareAndSet(snapshot, null);
            return null;
        }
        if (!snapshot.pos().equals(pos) || !PENDING.compareAndSet(snapshot, null)) return null;

        try {
            BlockEntity blockEntity = BlockEntity.loadStatic(pos,
                    Block.stateById(snapshot.blockStateId()), snapshot.blockEntityTag().copy());
            if (blockEntity != null) blockEntity.setLevel(level);
            return blockEntity;
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-MachineGUI] Failed to construct client Placebo menu snapshot at {}",
                    pos, exception);
            return null;
        }
    }
}
