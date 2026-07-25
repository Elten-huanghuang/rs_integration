package com.huanghuang.rsintegration.sidepanel;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class RSSidePanelClientPacketHandler {
    private RSSidePanelClientPacketHandler() {}

    static void onSync(RSSidePanelSyncPacket packet) {
        if (Minecraft.getInstance().player == null) return;
        RSSidePanelClient.onSyncReceived(packet);
    }

    static void onDelta(RSSidePanelDeltaPacket packet) {
        if (Minecraft.getInstance().player == null) return;
        for (RSSidePanelDeltaPacket.Entry entry : packet.entries) {
            RSSidePanelClient.onDeltaReceived(
                    entry.stackId, entry.stack, entry.timestamp, entry.craftable);
        }
    }

    static void onOperationResult(RSSidePanelOperationResultPacket packet) {
        RSSidePanelClient.onOperationResult(packet);
    }
}
