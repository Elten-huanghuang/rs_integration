package com.huanghuang.rsintegration.reforging;

import com.huanghuang.rsintegration.reforging.client.ReforgingRestockClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class ReforgingRestockClientPacketHandler {
    private ReforgingRestockClientPacketHandler() {}

    static void handle(ReforgingRestockResultPacket packet) {
        ReforgingRestockClient.accept(packet);
    }
}
