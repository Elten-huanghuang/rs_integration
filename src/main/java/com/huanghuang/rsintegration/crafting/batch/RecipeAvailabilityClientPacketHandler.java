package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.client.RecipeAvailabilityClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class RecipeAvailabilityClientPacketHandler {
    private RecipeAvailabilityClientPacketHandler() {}

    static void accept(RecipeAvailabilityResultPacket packet) {
        RecipeAvailabilityClient.accept(packet.key(), packet.ticket(), packet.state());
    }
}
