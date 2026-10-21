package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.machine.client.MachinePreferenceClient;
import com.huanghuang.rsintegration.machine.client.MachineHub;
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
