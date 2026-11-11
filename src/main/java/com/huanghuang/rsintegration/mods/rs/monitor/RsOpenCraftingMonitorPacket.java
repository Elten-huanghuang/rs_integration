package com.huanghuang.rsintegration.mods.rs.monitor;

import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 从 RS 合成终端打开同一网络中的原生合成监控器。 */
public record RsOpenCraftingMonitorPacket(GlobalPos terminal) {
    public void encode(FriendlyByteBuf buffer) { buffer.writeGlobalPos(terminal); }
    public static RsOpenCraftingMonitorPacket decode(FriendlyByteBuf buffer) {
        return new RsOpenCraftingMonitorPacket(buffer.readGlobalPos());
    }

    public static void handle(RsOpenCraftingMonitorPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> open(context.getSender(), packet.terminal()));
        context.setPacketHandled(true);
    }

    private static void open(ServerPlayer player, GlobalPos terminal) {
        if (player == null || !(player.containerMenu instanceof GridContainerMenu menu)
                || !menu.stillValid(player) || menu.getGrid().getGridType() != GridType.CRAFTING
                || !(menu.getBlockEntity() instanceof GridBlockEntity block) || block.getNode() == null
                || block.getLevel() == null || !block.getLevel().dimension().equals(terminal.dimension())
                || !block.getBlockPos().equals(terminal.pos())) return;
        var result = RsMonitorNavigationServer.openMonitor(player, block.getNode().getNetwork());
        if (result.messageKey != null) player.sendSystemMessage(Component.translatable(result.messageKey));
    }
}
