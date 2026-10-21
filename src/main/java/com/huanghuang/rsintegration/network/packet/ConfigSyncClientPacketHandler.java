package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.client.storage.UnifiedDiskClientVisibility;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class ConfigSyncClientPacketHandler {
    private ConfigSyncClientPacketHandler() {}

    static void handle(ConfigSyncPacket packet) {
        ClientSyncedConfig.apply(packet);
        UnifiedDiskClientVisibility.fromServer(packet.enableUnifiedDisk);
    }
}
