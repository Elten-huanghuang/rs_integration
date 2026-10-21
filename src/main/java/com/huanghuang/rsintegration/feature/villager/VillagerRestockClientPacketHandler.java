package com.huanghuang.rsintegration.feature.villager;

import com.huanghuang.rsintegration.feature.villager.client.VillagerRestockClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class VillagerRestockClientPacketHandler {
    private VillagerRestockClientPacketHandler() {}

    static void handle(VillagerRestockResultPacket packet) {
        VillagerRestockClient.accept(packet);
    }
}
