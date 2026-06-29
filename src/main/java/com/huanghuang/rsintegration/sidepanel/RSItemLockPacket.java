package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Set;
import java.util.function.Supplier;

/**
 * C2S packet: player Ctrl+Left Clicks an item in the side panel → toggle its lock state.
 */
public final class RSItemLockPacket {

    final ResourceLocation itemId;

    public RSItemLockPacket(ResourceLocation itemId) {
        this.itemId = itemId;
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(itemId);
    }

    static RSItemLockPacket decode(FriendlyByteBuf buf) {
        return new RSItemLockPacket(buf.readResourceLocation());
    }

    static void handle(RSItemLockPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        var ctx = ctxSupplier.get();
        ServerPlayer player = ctx.getSender();
        if (player == null || player instanceof net.minecraftforge.common.util.FakePlayer) {
            ctx.setPacketHandled(true);
            return;
        }
        ctx.enqueueWork(() -> {
            Set<ResourceLocation> newSet = PlayerLockManager.toggleLock(player, packet.itemId);
            RSSidePanelNetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new RSItemLockSyncPacket(new ArrayList<>(newSet)));
            RSIntegrationMod.LOGGER.debug("[RSI] Item lock toggled by {}: {} (now {} locked)",
                    player.getGameProfile().getName(), packet.itemId, newSet.size());
        });
        ctx.setPacketHandled(true);
    }
}
