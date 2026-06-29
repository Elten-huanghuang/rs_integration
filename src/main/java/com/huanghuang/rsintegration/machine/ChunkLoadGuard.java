package com.huanghuang.rsintegration.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Guards operations that require a loaded chunk + valid BlockEntity.
 *
 * <p>Unifies the "check chunk loaded → get BE → null-check BE" pattern
 * repeated across all 7 mod delegates and the machine GUI opener.</p>
 */
public final class ChunkLoadGuard {

    private ChunkLoadGuard() {}

    /**
     * Try to get a BlockEntity from the given dimension+pos.
     * If the chunk is not loaded, sends an error message to the player
     * and returns null.  If the BE is missing, sends a different error
     * and returns null.
     *
     * @param level      the target level (dimension), obtained via {@code server.getLevel(dim)}
     * @param pos        the block position
     * @param player     the player to notify on failure (may be null for silent checks)
     * @return the BlockEntity, or null if unavailable
     */
    @Nullable
    public static BlockEntity getBlockEntitySafe(ServerLevel level, BlockPos pos,
                                                  @Nullable ServerPlayer player) {
        if (!level.hasChunkAt(pos)) {
            if (player != null)
                player.sendSystemMessage(Component.translatable("rsi.error.chunk_unloaded"));
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            if (player != null)
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
            return null;
        }
        return be;
    }

    /**
     * Check whether the chunk at pos is loaded in the given level.
     * Does NOT trigger chunk loading. Safe to call from any thread.
     */
    public static boolean isChunkLoaded(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos);
    }

    /**
     * If the chunk is loaded, run the callback with the BlockEntity.
     * Otherwise, notify the player.
     */
    public static void ifLoaded(ServerLevel level, BlockPos pos, ServerPlayer player,
                                 Consumer<BlockEntity> action) {
        BlockEntity be = getBlockEntitySafe(level, pos, player);
        if (be != null) action.accept(be);
    }
}
