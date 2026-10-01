package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 每类快照独立提交；分包期间不会清掉另一类资源。 */
public record UnifiedGridUpdatePacket(int containerId, UUID session, GridResourceKind kind, int epoch,
                                      int sequence, boolean begin, boolean end, boolean enabled,
                                      boolean canCraft, byte[] payload) {
    public static final int TARGET_BYTES = 192 * 1024;
    public static final int MAX_BYTES = 768 * 1024;
    public static final int MAX_ENTRIES = 4096;

    public static void encode(UnifiedGridUpdatePacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.containerId);
        buf.writeUUID(packet.session);
        buf.writeByte(packet.kind.ordinal());
        buf.writeVarInt(packet.epoch);
        buf.writeVarInt(packet.sequence);
        buf.writeBoolean(packet.begin);
        buf.writeBoolean(packet.end);
        buf.writeBoolean(packet.enabled);
        buf.writeBoolean(packet.canCraft);
        buf.writeByteArray(packet.payload);
    }

    public static UnifiedGridUpdatePacket decode(FriendlyByteBuf buf) {
        return new UnifiedGridUpdatePacket(buf.readVarInt(), buf.readUUID(), GridResourceKind.fromId(buf.readUnsignedByte()),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readByteArray(MAX_BYTES));
    }

    public List<UnifiedGridEntry> entries() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            List<UnifiedGridEntry> result = new ArrayList<>();
            while (buf.isReadable()) {
                if (result.size() >= MAX_ENTRIES) throw new IllegalArgumentException("条目数量超限");
                result.add(UnifiedGridEntry.read(buf, kind));
            }
            return result;
        } finally { buf.release(); }
    }

    public static void handle(UnifiedGridUpdatePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> UnifiedGridClientPacketHandler.handle(packet)));
        context.setPacketHandled(true);
    }

    public void send(ServerPlayer player) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), this);
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.UNIFIED_GRID_UPDATE, UnifiedGridUpdatePacket.class,
                UnifiedGridUpdatePacket::encode, UnifiedGridUpdatePacket::decode, UnifiedGridUpdatePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        UnifiedGridActionPacket.register();
    }
}
