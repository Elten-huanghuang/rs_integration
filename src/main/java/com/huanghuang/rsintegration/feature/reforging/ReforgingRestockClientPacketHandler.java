package com.huanghuang.rsintegration.feature.reforging;

import com.huanghuang.rsintegration.feature.reforging.client.ReforgingRestockClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class ReforgingRestockClientPacketHandler {
    private ReforgingRestockClientPacketHandler() {}

    static void handle(ReforgingRestockResultPacket packet) {
        ReforgingRestockClient.accept(packet);
    }
}
