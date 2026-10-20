package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.mods.ironfurnaces.client.IronFurnaceJeiRefresh;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

@OnlyIn(Dist.CLIENT)
final class RSBindingSyncClientPacketHandler {
    private RSBindingSyncClientPacketHandler() {}

    static void handle(RSBindingSyncPacket packet) {
        BindingCache.getInstance().updateBindings(packet.bindings());
        if (ModList.get().isLoaded(ModIds.JEI)) {
            IronFurnaceJeiRefresh.refreshIfOpen();
        }
    }
}
