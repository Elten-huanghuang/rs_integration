package com.huanghuang.rsintegration.network.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Extension point for opening mod-specific machine GUIs.
 * Each supported machine type registers an opener in BlockGuiRegistry.
 */
@FunctionalInterface
public interface IMachineGuiOpener {
    /**
     * Open the GUI for the given machine.
     * @param player the server player requesting the GUI
     * @param machine the BlockEntity (guaranteed non-null and type-matched by the registry)
     * @param dim the dimension the machine is in
     * @param pos the machine position
     */
    void openGui(ServerPlayer player, BlockEntity machine, ResourceKey<Level> dim, BlockPos pos);
}
