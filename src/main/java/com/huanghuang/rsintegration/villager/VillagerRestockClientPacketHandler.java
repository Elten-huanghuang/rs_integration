package com.huanghuang.rsintegration.villager;

import com.huanghuang.rsintegration.villager.client.VillagerRestockClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class VillagerRestockClientPacketHandler {
    private VillagerRestockClientPacketHandler() {}

    static void handle(VillagerRestockResultPacket packet) {
        VillagerRestockClient.accept(packet);
    }
}
