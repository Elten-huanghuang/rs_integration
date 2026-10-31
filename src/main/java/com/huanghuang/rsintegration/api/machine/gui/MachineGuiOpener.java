package com.huanghuang.rsintegration.api.machine.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Invoked on the server thread, after RSI authorizes the bound remote target. */
@FunctionalInterface
public interface MachineGuiOpener {
    /** Open a real server menu synchronously. Never authorize targets in this callback. */
    void open(ServerPlayer player, BlockEntity machine, ResourceKey<Level> dimension, BlockPos position);
}
