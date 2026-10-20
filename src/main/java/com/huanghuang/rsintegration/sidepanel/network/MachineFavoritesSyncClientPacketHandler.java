package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.sidepanel.client.MachinePreferenceClient;
import com.huanghuang.rsintegration.machine.MachineHub;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class MachineFavoritesSyncClientPacketHandler {
    private MachineFavoritesSyncClientPacketHandler() {}

    static void handle(MachineFavoritesSyncPacket packet) {
        MachinePreferenceClient.update(packet.favorites(), packet.blocked());
        MachineHub.refreshMachines();
    }
}
