package com.huanghuang.rsintegration.enchanting;

import com.huanghuang.rsintegration.enchanting.client.EnchantingRestockClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class EnchantingRestockClientPacketHandler {
    private EnchantingRestockClientPacketHandler() {}

    static void handle(EnchantingRestockResultPacket packet) {
        EnchantingRestockClient.accept(packet);
    }
}
