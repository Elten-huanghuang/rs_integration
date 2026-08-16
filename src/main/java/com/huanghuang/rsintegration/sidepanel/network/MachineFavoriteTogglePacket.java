package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoriteKey;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoritesSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MachineFavoriteTogglePacket(MachineFavoriteKey key) {
    public static void encode(MachineFavoriteTogglePacket packet, FriendlyByteBuf buf) {
        packet.key.encode(buf);
    }

    public static MachineFavoriteTogglePacket decode(FriendlyByteBuf buf) {
        return new MachineFavoriteTogglePacket(MachineFavoriteKey.decode(buf));
    }

    public static void handle(MachineFavoriteTogglePacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            context.enqueueWork(() -> {
                var binding = AltarBindingRegistry.findBindingEntry(
                        player, packet.key.dimension(), packet.key.pos());
                if (binding != null && packet.key.blockKey().equals(binding.blockKey())
                        && BindingEventHandler.supportsGuiByBlockKey(binding.blockKey())) {
                    MachineFavoritesSavedData.ToggleResult result =
                            MachineFavoritesSavedData.get(player.server)
                                    .toggle(player.getUUID(), packet.key);
                    if (result == MachineFavoritesSavedData.ToggleResult.LIMIT_REACHED) {
                        player.displayClientMessage(Component.translatable(
                                "rsi.hub.favorite_limit",
                                MachineFavoritesSavedData.MAX_FAVORITES), true);
                    }
                }
                RSSidePanelNetworkHandler.sendMachineFavoritesSync(player);
            });
        }
        context.setPacketHandled(true);
    }
}
