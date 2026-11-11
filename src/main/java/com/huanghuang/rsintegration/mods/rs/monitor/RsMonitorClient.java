package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.inventory.player.PlayerSlot;
import com.refinedmods.refinedstorage.item.NetworkItem;
import com.refinedmods.refinedstorage.item.WirelessCraftingMonitorItem;
import com.refinedmods.refinedstorage.network.OpenNetworkItemMessage;
import com.refinedmods.refinedstorage.blockentity.craftingmonitor.ICraftingMonitor;

import java.util.UUID;
import java.util.Optional;

public final class RsMonitorClient {
    private RsMonitorClient() {}

    /** RS 原生接口重复选择会反选；纵向列表和确认回执应保持当前选择。 */
    public static boolean select(ICraftingMonitor monitor, UUID craft) {
        if (monitor.getTabSelected().filter(craft::equals).isPresent()) return false;
        monitor.onTabSelectionChanged(Optional.of(craft));
        return true;
    }

    /** 与 RS 自带快捷键相同的开包路径，由 RS 验证距离、能量和权限。 */
    public static boolean openWirelessMonitor() {
        var player = Minecraft.getInstance().player;
        if (player == null) return false;
        int selected = player.getInventory().selected;
        if (openSlot(new PlayerSlot(selected))) return true;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (slot != selected && openSlot(new PlayerSlot(slot))) return true;
        }
        return false;
    }

    private static boolean openSlot(PlayerSlot slot) {
        var player = Minecraft.getInstance().player;
        var stack = slot.getStackFromSlot(player);
        if (!(stack.getItem() instanceof WirelessCraftingMonitorItem) || !NetworkItem.isValid(stack)) return false;
        RS.NETWORK_HANDLER.sendToServer(new OpenNetworkItemMessage(slot));
        return true;
    }

    public static void accept(RsMonitorScopePacket packet) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu.containerId == packet.containerId()
                && player.containerMenu instanceof MonitorMenuAccess access) {
            access.rsi$monitorScope().accept(packet.scope());
        }
    }

    public static void cancel(AbstractContainerMenu menu, UUID craftId) {
        if (!(menu instanceof MonitorMenuAccess access)) return;
        var scope = access.rsi$monitorScope().current();
        if (scope == null || !scope.crafts().contains(craftId)) return;
        NetworkHandler.CHANNEL.sendToServer(new RsMonitorCancelPacket(
                menu.containerId, scope.session(), craftId));
    }

    public static void accept(RsMonitorMaterialsPacket packet) {
        var player = Minecraft.getInstance().player;
        if (player == null || player.containerMenu.containerId != packet.containerId()
                || !(player.containerMenu instanceof MonitorMenuAccess access)) return;
        var scope = access.rsi$monitorScope().current();
        if (scope != null && access.rsi$monitorScope().allows(packet.session(), packet.craft(), scope.network())) {
            access.rsi$monitorScope().materials().accept(packet);
        }
    }
}
