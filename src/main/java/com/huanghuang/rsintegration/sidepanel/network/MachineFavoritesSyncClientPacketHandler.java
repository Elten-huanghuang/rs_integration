package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.sidepanel.client.MachineFavoritesClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class MachineFavoritesSyncClientPacketHandler {
    private MachineFavoritesSyncClientPacketHandler() {}

    static void handle(MachineFavoritesSyncPacket packet) {
        MachineFavoritesClient.update(packet.favorites());
    }
}
