package com.huanghuang.rsintegration.machine.client;

import com.huanghuang.rsintegration.network.machine.bd.BeyondDimensionsBindingSyncPacket;
import com.huanghuang.rsintegration.machine.client.data.BindingCache;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** Client-side application of the standalone BD binding snapshot. */
@OnlyIn(Dist.CLIENT)
public final class BeyondDimensionsBindingSyncClientPacketHandler {
    private BeyondDimensionsBindingSyncClientPacketHandler() {}

    public static void handle(BeyondDimensionsBindingSyncPacket packet) {
        List<BindingInfo> complete = BindingSyncChunkAccumulator.accept(
                packet.generation(), packet.chunkIndex(), packet.totalChunks(),
                packet.bindings());
        if (complete == null) return;
        BindingCache.getInstance().updateBindings(complete);
        MachineHub.refreshMachines();
        BeyondDimensionsMachineHubClient.markAuthoritativeBindingSync();
    }
}
