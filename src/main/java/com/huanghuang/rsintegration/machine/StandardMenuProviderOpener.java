package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.network.gui.IMachineGuiOpener;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkHooks;

/**
 * Opens a machine GUI via the standard {@link NetworkHooks#openScreen} path.
 * Works for any {@link BlockEntity} that implements {@link MenuProvider}.
 */
public enum StandardMenuProviderOpener implements IMachineGuiOpener {
    INSTANCE;

    @Override
    public void openGui(ServerPlayer player, BlockEntity machine, ResourceKey<Level> dim, BlockPos pos) {
        if (machine instanceof MenuProvider provider) {
            NetworkHooks.openScreen(player, provider, pos);
        }
    }
}
