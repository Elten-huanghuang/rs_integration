package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.sidepanel.client.WorldPickClient;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class RSSidePanelClientPacketHandler {
    private RSSidePanelClientPacketHandler() {}

    static void onSync(RSSidePanelSyncPacket packet) {
        if (!RSSidePanelModule.isEnabled()) return;
        if (Minecraft.getInstance().player == null) return;
        RSSidePanelClient.onSyncReceived(packet);
    }

    static void onDelta(RSSidePanelDeltaPacket packet) {
        if (!RSSidePanelModule.isEnabled()) return;
        if (Minecraft.getInstance().player == null) return;
        for (RSSidePanelDeltaPacket.Entry entry : packet.entries) {
            RSSidePanelClient.onDeltaReceived(
                    entry.stackId, entry.stack, entry.timestamp, entry.craftable);
        }
    }

    static void onOperationResult(RSSidePanelOperationResultPacket packet) {
        WorldPickClient.onOperationResult(
                packet.operationId(), packet.success(), packet.actualCount());
        if (RSSidePanelModule.isEnabled()) RSSidePanelClient.onOperationResult(packet);
    }
}
