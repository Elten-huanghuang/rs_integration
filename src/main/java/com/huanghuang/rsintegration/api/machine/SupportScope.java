package com.huanghuang.rsintegration.api.machine;

import net.minecraft.core.BlockPos;

import java.util.List;

public record SupportScope(
        List<BlockPos> supportOffsets,
        List<BlockPos> adjacentBlocks,
        boolean structural
) {
    public SupportScope {
        supportOffsets = supportOffsets == null ? List.of() : List.copyOf(supportOffsets);
        adjacentBlocks = adjacentBlocks == null ? List.of() : List.copyOf(adjacentBlocks);
    }
}
