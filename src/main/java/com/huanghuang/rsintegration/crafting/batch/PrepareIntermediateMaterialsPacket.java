package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Explicit C2S request that can only execute independently craftable intermediates. */
public final class PrepareIntermediateMaterialsPacket {

    private final GenericCraftPacket request;

    public PrepareIntermediateMaterialsPacket(GenericCraftPacket request) {
        if (request == null) throw new IllegalArgumentException("request cannot be null");
        if (request.isPreviewRequest()) {
            throw new IllegalArgumentException("preparation request cannot be a preview");
        }
        this.request = request;
    }

    public void encode(FriendlyByteBuf buf) {
        request.encode(buf);
    }

    public static PrepareIntermediateMaterialsPacket decode(FriendlyByteBuf buf) {
        return new PrepareIntermediateMaterialsPacket(GenericCraftPacket.decode(buf));
    }

    public static void handle(PrepareIntermediateMaterialsPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        RSIntegrationMod.LOGGER.info(
                "[RSI-Preparation] dedicated request received recipe={}",
                packet.request.recipeId());
        GenericCraftPacket.handlePreparation(packet.request, contextSupplier);
    }

    GenericCraftPacket request() {
        return request;
    }

}
