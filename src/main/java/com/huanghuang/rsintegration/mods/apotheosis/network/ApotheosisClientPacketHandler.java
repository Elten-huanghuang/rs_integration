package com.huanghuang.rsintegration.mods.apotheosis.network;

import com.huanghuang.rsintegration.mods.apotheosis.client.ApotheosisLibraryClientEvents;
import com.huanghuang.rsintegration.mods.apotheosis.client.ApothSpawnerUpgradeScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class ApotheosisClientPacketHandler {
    private ApotheosisClientPacketHandler() {}

    static void onImportResult(ApotheosisLibraryImportResultPacket packet) {
        ApotheosisLibraryClientEvents.acceptImportResult(packet);
    }

    static void onScanResponse(ApotheosisLibraryScanResponsePacket packet) {
        ApotheosisLibraryClientEvents.acceptScan(packet);
    }

    static void onSpawnerState(ApothSpawnerStatePacket packet) {
        ApothSpawnerUpgradeScreen.accept(packet);
    }
}
