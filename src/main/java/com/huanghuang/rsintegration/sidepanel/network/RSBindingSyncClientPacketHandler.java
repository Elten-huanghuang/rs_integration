package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.mods.ironfurnaces.client.IronFurnaceJeiRefresh;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.sidepanel.client.BindingSyncChunkAccumulator;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

import java.util.List;

@OnlyIn(Dist.CLIENT)
final class RSBindingSyncClientPacketHandler {
    private RSBindingSyncClientPacketHandler() {}

    static void handle(RSBindingSyncPacket packet) {
        List<BindingInfo> complete = BindingSyncChunkAccumulator.accept(
                packet.generation(), packet.chunkIndex(), packet.totalChunks(),
                packet.bindings());
        if (complete == null) return;
        BindingCache.getInstance().updateBindings(complete);
        MachineHub.refreshMachines();
        if (ModList.get().isLoaded(ModIds.JEI)) {
            IronFurnaceJeiRefresh.refreshIfOpen();
        }
    }
}
