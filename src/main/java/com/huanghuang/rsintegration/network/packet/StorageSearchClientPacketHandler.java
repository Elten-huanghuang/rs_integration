package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.client.StorageSearchClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Dist-isolated bridge for the terminal search packet. */
@OnlyIn(Dist.CLIENT)
public final class StorageSearchClientPacketHandler {
    private StorageSearchClientPacketHandler() {}

    public static void apply(int containerId, String text) {
        StorageSearchClient.applyServerSearchText(containerId, text);
    }
}
