package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.sidepanel.client.BindingSyncChunkAccumulator;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** Client-side application of the standalone BD binding snapshot. */
@OnlyIn(Dist.CLIENT)
final class BeyondDimensionsBindingSyncClientPacketHandler {
    private BeyondDimensionsBindingSyncClientPacketHandler() {}

    static void handle(BeyondDimensionsBindingSyncPacket packet) {
        List<BindingInfo> complete = BindingSyncChunkAccumulator.accept(
                packet.generation(), packet.chunkIndex(), packet.totalChunks(),
                packet.bindings());
        if (complete == null) return;
        BindingCache.getInstance().updateBindings(complete);
        MachineHub.refreshMachines();
        BeyondDimensionsMachineHubClient.markAuthoritativeBindingSync();
    }
}
