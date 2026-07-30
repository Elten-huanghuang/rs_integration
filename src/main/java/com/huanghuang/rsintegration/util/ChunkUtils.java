package com.huanghuang.rsintegration.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class ChunkUtils {
    private ChunkUtils() {}

    /** Check if the chunk at pos is loaded WITHOUT forcing a load. */
    public static boolean isChunkLoaded(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos);
    }

    /** Check every chunk intersecting a horizontal block radius without loading any of them. */
    public static boolean isAreaLoaded(ServerLevel level, BlockPos center, int radius) {
        int minChunkX = (center.getX() - Math.max(0, radius)) >> 4;
        int maxChunkX = (center.getX() + Math.max(0, radius)) >> 4;
        int minChunkZ = (center.getZ() - Math.max(0, radius)) >> 4;
        int maxChunkZ = (center.getZ() + Math.max(0, radius)) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunkAt(new BlockPos(chunkX << 4, center.getY(), chunkZ << 4))) {
                    return false;
                }
            }
        }
        return true;
    }

}
