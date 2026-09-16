package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.client.JeiNetworkItemCache;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Dist-isolated bridge for the JEI inventory packet. */
@OnlyIn(Dist.CLIENT)
public final class JeiNetworkInventoryClientPacketHandler {
    private JeiNetworkInventoryClientPacketHandler() {}

    public static void accept(JeiNetworkInventoryPacket packet) {
        JeiNetworkItemCache.INSTANCE.accept(packet);
    }
}
