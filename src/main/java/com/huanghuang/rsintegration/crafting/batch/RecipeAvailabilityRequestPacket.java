package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.availability.MaterialAvailability;
import com.huanghuang.rsintegration.crafting.availability.RecipeAvailabilityKey;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.Util;

import java.util.WeakHashMap;
import java.util.Map;
import java.util.function.Supplier;

public record RecipeAvailabilityRequestPacket(RecipeAvailabilityKey key, long ticket) {
    private static final Map<ServerPlayer, Window> WINDOWS = new WeakHashMap<>();

    public void encode(FriendlyByteBuf buf) {
        key.encode(buf);
        buf.writeLong(ticket);
    }

    public static RecipeAvailabilityRequestPacket decode(FriendlyByteBuf buf) {
        return new RecipeAvailabilityRequestPacket(RecipeAvailabilityKey.decode(buf), buf.readLong());
    }

    public static void handle(RecipeAvailabilityRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        var player = context.getSender();
        if (player != null) context.enqueueWork(() -> {
            long now = Util.getMillis();
            Window window = WINDOWS.computeIfAbsent(player, ignored -> new Window());
            if (now < window.start || now - window.start >= 1_000) {
                window.start = now;
                window.requests = 0;
            }
            if (++window.requests > 40) return;
            MaterialAvailability state = MaterialAvailability.UNKNOWN;
            try {
                state = RecipeAvailabilityChecker.check(player, packet.key);
            } catch (RuntimeException | LinkageError failure) {
                RSIntegrationMod.LOGGER.debug("[RSI-Materials] Cannot check {}", packet.key.recipeId(), failure);
            }
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new RecipeAvailabilityResultPacket(packet.key, packet.ticket, state));
        });
        context.setPacketHandled(true);
    }

    private static final class Window {
        long start;
        int requests;
    }
}
