package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;

import java.util.Optional;
import java.util.UUID;

/** 只同步两类库存的摘要；不读取全部 Stack，也不改变物理盘 NBT。 */
public final class UnifiedDiskTooltipPackets {
    private UnifiedDiskTooltipPackets() {}

    public static boolean matches(ItemStack stack, UUID world, UUID disk) {
        return stack.getItem() instanceof UnifiedDiskItem item && item.isValid(stack)
                && stack.getTag().getInt("Format") == 1 && world.equals(item.worldId(stack)) && disk.equals(item.getId(stack));
    }

    public static boolean visible(Player player, UUID world, UUID disk) {
        if (matches(player.containerMenu.getCarried(), world, disk)) return true;
        for (var slot : player.containerMenu.slots) if (matches(slot.getItem(), world, disk)) return true;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (matches(player.getInventory().getItem(slot), world, disk)) return true;
        }
        return false;
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.UNIFIED_DISK_TOOLTIP_REQUEST, UnifiedDiskTooltipRequestPacket.class,
                UnifiedDiskTooltipRequestPacket::encode, UnifiedDiskTooltipRequestPacket::decode, UnifiedDiskTooltipRequestPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.UNIFIED_DISK_TOOLTIP_RESPONSE, UnifiedDiskTooltipResponsePacket.class,
                UnifiedDiskTooltipResponsePacket::encode, UnifiedDiskTooltipResponsePacket::decode, UnifiedDiskTooltipResponsePacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
