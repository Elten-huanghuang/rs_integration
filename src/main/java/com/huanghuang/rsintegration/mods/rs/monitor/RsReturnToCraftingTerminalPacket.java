package com.huanghuang.rsintegration.mods.rs.monitor;

import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/** 返回当前监控网络的合成终端；客户端位置仅作为优先选择，不能指定其他网络。 */
public record RsReturnToCraftingTerminalPacket(int containerId, @Nullable GlobalPos terminal) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeBoolean(terminal != null);
        if (terminal != null) buffer.writeGlobalPos(terminal);
    }

    public static RsReturnToCraftingTerminalPacket decode(FriendlyByteBuf buffer) {
        return new RsReturnToCraftingTerminalPacket(buffer.readVarInt(), buffer.readBoolean() ? buffer.readGlobalPos() : null);
    }

    public static void handle(RsReturnToCraftingTerminalPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            var result = RsMonitorNavigationServer.returnToTerminal(player, packet.containerId(), packet.terminal());
            if (result.messageKey != null) player.sendSystemMessage(Component.translatable(result.messageKey));
        });
        context.setPacketHandled(true);
    }
}
