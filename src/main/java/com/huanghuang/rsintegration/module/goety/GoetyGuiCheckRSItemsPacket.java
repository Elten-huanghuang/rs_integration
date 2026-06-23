package com.huanghuang.rsintegration.module.goety;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

public final class GoetyGuiCheckRSItemsPacket {

    final ResourceLocation recipeId;
    @Nullable final ResourceLocation dim;
    final BlockPos altarPos;

    public GoetyGuiCheckRSItemsPacket(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos altarPos) {
        this.recipeId = recipeId;
        this.dim = dim;
        this.altarPos = altarPos;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(recipeId);
        buf.writeBoolean(dim != null);
        if (dim != null) buf.writeResourceLocation(dim);
        buf.writeBlockPos(altarPos);
    }

    public static GoetyGuiCheckRSItemsPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        ResourceLocation dim = buf.readBoolean() ? buf.readResourceLocation() : null;
        return new GoetyGuiCheckRSItemsPacket(recipeId, dim, buf.readBlockPos());
    }

    public static void handle(GoetyGuiCheckRSItemsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            try {
                boolean[] results = RSAvailabilityChecker.check(player, packet.recipeId, packet.dim, packet.altarPos);
                if (results != null) {
                    GoetyRSNetworkHandler.sendRSResult(player, packet.recipeId, results);
                }
            } catch (NoClassDefFoundError e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Goety] RS item check skipped: Goety classes not available");
            }
        });
        context.setPacketHandled(true);
    }

}
