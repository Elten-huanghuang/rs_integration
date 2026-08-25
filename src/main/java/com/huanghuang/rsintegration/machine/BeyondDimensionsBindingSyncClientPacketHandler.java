package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Client-side application of the standalone BD binding snapshot. */
@OnlyIn(Dist.CLIENT)
final class BeyondDimensionsBindingSyncClientPacketHandler {
    private BeyondDimensionsBindingSyncClientPacketHandler() {}

    static void handle(BeyondDimensionsBindingSyncPacket packet) {
        BindingCache.getInstance().updateBindings(packet.bindings());
        BeyondDimensionsMachineHubClient.markAuthoritativeBindingSync();
        MachineHub.refreshMachines();
    }
}
