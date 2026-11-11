package com.huanghuang.rsintegration.mods.rs.monitor;

import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 只更新当前原生菜单的选择项，复用原生任务/材料同步。 */
public record RsNetworkMonitorSelectPacket(int containerId, @Nullable UUID taskId) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeBoolean(taskId != null);
        if (taskId != null) buffer.writeUUID(taskId);
    }
    public static RsNetworkMonitorSelectPacket decode(FriendlyByteBuf buffer) {
        return new RsNetworkMonitorSelectPacket(buffer.readVarInt(), buffer.readBoolean() ? buffer.readUUID() : null);
    }
    public static void handle(RsNetworkMonitorSelectPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null || !(player.containerMenu instanceof CraftingMonitorContainerMenu menu)
                    || menu.containerId != packet.containerId() || !menu.stillValid(player)
                    || !(menu.getCraftingMonitor() instanceof NetworkCraftingMonitor monitor)) return;
            monitor.onTabSelectionChanged(Optional.ofNullable(packet.taskId()));
        });
        context.setPacketHandled(true);
    }
}
