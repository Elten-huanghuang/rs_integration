package com.huanghuang.rsintegration.module.goety;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class GoetyGuiRSItemsResultPacket {

    final ResourceLocation recipeId;
    final boolean[] results;

    public GoetyGuiRSItemsResultPacket(ResourceLocation recipeId, boolean[] results) {
        this.recipeId = recipeId;
        this.results = results;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(recipeId);
        buf.writeVarInt(results.length);
        for (boolean b : results) {
            buf.writeBoolean(b);
        }
    }

    public static GoetyGuiRSItemsResultPacket decode(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        int len = buf.readVarInt();
        boolean[] results = new boolean[len];
        for (int i = 0; i < len; i++) {
            results[i] = buf.readBoolean();
        }
        return new GoetyGuiRSItemsResultPacket(id, results);
    }

    public static void handle(GoetyGuiRSItemsResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> RSClientAvailabilityCache.put(packet.recipeId, packet.results));
        }
        context.setPacketHandled(true);
    }
}
