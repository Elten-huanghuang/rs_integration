package com.huanghuang.rsintegration.mods.rs.monitor;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

import javax.annotation.Nullable;

/** 服务端持有真实终端和原生参数监听，客户端使用不依赖区块的视图。 */
public final class RemoteCraftingGridMenu extends GridContainerMenu {
    private final GlobalPos terminal;
    private final INetwork network;

    RemoteCraftingGridMenu(IGrid grid, @Nullable GridBlockEntity block, Player player, int id, GlobalPos terminal) {
        super(grid, block, player, id);
        this.terminal = terminal;
        this.network = block == null ? null : block.getNode().getNetwork();
    }

    public GlobalPos terminal() { return terminal; }
    @Override public MenuType<?> getType() { return RsMonitorMenus.REMOTE_CRAFTING_GRID.get(); }

    @Override public boolean stillValid(Player player) {
        if (network == null) return getBlockEntity() == null && player == getPlayer();
        return player == getPlayer() && network.canRun()
                && network.getSecurityManager().hasPermission(Permission.MODIFY, player)
                && network.getSecurityManager().hasPermission(Permission.AUTOCRAFTING, player)
                && getBlockEntity() instanceof GridBlockEntity block
                && RsMonitorNavigationServer.isTerminal(block, network);
    }
}
