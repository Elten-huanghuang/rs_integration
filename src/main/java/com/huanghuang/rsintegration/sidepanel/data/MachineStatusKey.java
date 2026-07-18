package com.huanghuang.rsintegration.sidepanel.data;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Immutable dimension and block position identity for machine status. */
public record MachineStatusKey(ResourceLocation dimension, BlockPos position) {
    public MachineStatusKey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        position = position.immutable();
    }
}
