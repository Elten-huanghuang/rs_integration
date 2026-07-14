package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.CraftStatusRequestPacket;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

/** Client connection lifecycle for progress status synchronization. */
public final class CraftProgressClientEvents {

    private CraftProgressClientEvents() {}

    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        // Ask after the play connection exists. The server responds with a
        // started+progress pair for every active craft owned by this player.
        BatchCraftNetworkHandler.CHANNEL.sendToServer(new CraftStatusRequestPacket());
    }

    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CraftProgressTracker.clear();
    }
}
