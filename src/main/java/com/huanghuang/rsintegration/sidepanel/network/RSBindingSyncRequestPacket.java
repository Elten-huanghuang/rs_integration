package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.common.util.FakePlayer;

import java.util.function.Supplier;

/**
 * Client -> server: refresh machine bindings without opening the side-panel
 * storage-cache listener or requesting an item snapshot.
 */
public final class RSBindingSyncRequestPacket {

    public RSBindingSyncRequestPacket() {}

    public static void encode(RSBindingSyncRequestPacket packet, FriendlyByteBuf buf) {}

    public static RSBindingSyncRequestPacket decode(FriendlyByteBuf buf) {
        return new RSBindingSyncRequestPacket();
    }

    public static void handle(RSBindingSyncRequestPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null && !(player instanceof FakePlayer)) {
            context.enqueueWork(() -> {
                if (RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get()) {
                    RSSidePanelNetworkHandler.sendBindingSync(player);
                }
            });
        }
        context.setPacketHandled(true);
    }
}
