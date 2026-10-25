package com.huanghuang.rsintegration.api.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

public record MachineContext(
        ResourceLocation integrationId,
        ServerLevel level,
        BlockPos position,
        @Nullable ServerPlayer player
) {
    public MachineContext {
        if (integrationId == null || level == null || position == null) {
            throw new IllegalArgumentException("machine context is incomplete");
        }
    }
}
