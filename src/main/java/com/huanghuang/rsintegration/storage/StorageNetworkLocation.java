package com.huanghuang.rsintegration.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

/** 存储网络可用于保底掉落的稳定世界坐标。 */
public record StorageNetworkLocation(ResourceKey<Level> dimension, BlockPos position) {
    public StorageNetworkLocation {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
    }

    public Optional<ServerLevel> level(@Nullable MinecraftServer server) {
        return server == null ? Optional.empty()
                : Optional.ofNullable(server.getLevel(dimension));
    }
}
