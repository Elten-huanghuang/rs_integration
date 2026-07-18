package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class LithumAltarStatusRequestPacket {
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();
    private final ResourceLocation dimension;
    private final BlockPos pos;

    public LithumAltarStatusRequestPacket(ResourceLocation dimension, BlockPos pos) {
        this.dimension = dimension;
        this.pos = pos;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
    }

    public static LithumAltarStatusRequestPacket decode(FriendlyByteBuf buf) {
        return new LithumAltarStatusRequestPacket(buf.readResourceLocation(), buf.readBlockPos());
    }

    public static void handle(LithumAltarStatusRequestPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) return;
            long now = System.currentTimeMillis();
            long last = LAST_REQUEST.getOrDefault(player.getUUID(), 0L);
            if (now - last < 200) return;
            LAST_REQUEST.put(player.getUUID(), now);
            ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, packet.dimension);
            ServerLevel level = player.server.getLevel(key);
            if (level == null || player.level() != level || !level.isLoaded(packet.pos)) return;
            if (player.distanceToSqr(packet.pos.getX() + 0.5,
                    packet.pos.getY() + 0.5, packet.pos.getZ() + 0.5) > 32 * 32) return;
            LithumAltarStateReader.Snapshot state = LithumAltarStateReader.read(level, packet.pos);
            if (state == null) return;
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new LithumAltarStatusPacket(LithumAltarStatusSnapshot.from(
                            packet.dimension, packet.pos, state)));
        });
        ctx.get().setPacketHandled(true);
    }

    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LAST_REQUEST.remove(player.getUUID());
        }
    }
}
