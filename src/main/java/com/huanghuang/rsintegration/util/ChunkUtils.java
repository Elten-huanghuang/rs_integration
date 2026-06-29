package com.huanghuang.rsintegration.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class ChunkUtils {
    private ChunkUtils() {}

    /** Pre-load chunks around a position. Safe to call from server thread. */
    public static void ensureChunksLoaded(ServerLevel level, BlockPos center, int radius) {
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        int cr = (radius + 15) >> 4;
        for (int dx = -cr; dx <= cr; dx++) {
            for (int dz = -cr; dz <= cr; dz++) {
                level.getChunk(cx + dx, cz + dz);
            }
        }
    }

    /** Check if the chunk at pos is loaded WITHOUT forcing a load. */
    public static boolean isChunkLoaded(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos);
    }

    /** Load the chunk at pos (server thread only). Returns true if now loaded. */
    public static boolean loadChunk(ServerLevel level, BlockPos pos) {
        if (level.hasChunkAt(pos)) return true;
        level.getChunk(pos);
        return level.hasChunkAt(pos);
    }
}
