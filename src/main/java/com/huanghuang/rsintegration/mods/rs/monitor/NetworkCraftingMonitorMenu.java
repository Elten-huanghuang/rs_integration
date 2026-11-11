package com.huanghuang.rsintegration.mods.rs.monitor;

import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

/** 复用 RS 原生菜单及监听生命周期，仅补上没有方块实体时的网络有效性检查。 */
public final class NetworkCraftingMonitorMenu extends CraftingMonitorContainerMenu {
    private final NetworkCraftingMonitor monitor;

    NetworkCraftingMonitorMenu(MenuType<CraftingMonitorContainerMenu> type, NetworkCraftingMonitor monitor,
                              Player player, int containerId) {
        super(type, monitor, null, player, containerId);
        this.monitor = monitor;
    }

    @Override public boolean stillValid(Player player) { return monitor.stillValid(player); }
}
