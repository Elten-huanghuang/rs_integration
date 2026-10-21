package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.mods.ironfurnaces.client.IronFurnaceJeiRefresh;
import com.huanghuang.rsintegration.machine.client.MachineHub;
import com.huanghuang.rsintegration.machine.client.BindingSyncChunkAccumulator;
import com.huanghuang.rsintegration.machine.client.data.BindingCache;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
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
