package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.core.UnifiedDiskSummary;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/** 仅允许查询玩家当前可见的磁盘，服务器限制请求频率。 */
public record UnifiedDiskTooltipRequestPacket(UUID world, UUID disk) {
    public static void encode(UnifiedDiskTooltipRequestPacket packet, FriendlyByteBuf buf) { buf.writeUUID(packet.world); buf.writeUUID(packet.disk); }
    public static UnifiedDiskTooltipRequestPacket decode(FriendlyByteBuf buf) { return new UnifiedDiskTooltipRequestPacket(buf.readUUID(), buf.readUUID()); }
    public static void handle(UnifiedDiskTooltipRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !UnifiedDiskTooltipPackets.visible(player, packet.world, packet.disk)) return;
            UnifiedDiskManager manager = UnifiedDiskManager.get(player.serverLevel());
            if (!manager.allowTooltipRequest(player.getUUID())) return;
            boolean sameWorld = packet.world.equals(manager.worldId());
            UnifiedDiskSummary summary = sameWorld && manager.enabled() ? manager.summary(packet.disk) : null;
            UnifiedDiskFailure failure = sameWorld || manager.worldId() == null
                    ? manager.failure(packet.disk) : UnifiedDiskFailure.wrongWorld();
            if (failure != null && sameWorld) manager.notifyFailure(packet.disk, player.getUUID());
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new UnifiedDiskTooltipResponsePacket(packet.world, packet.disk, summary, failure));
        });
        context.setPacketHandled(true);
    }
}
