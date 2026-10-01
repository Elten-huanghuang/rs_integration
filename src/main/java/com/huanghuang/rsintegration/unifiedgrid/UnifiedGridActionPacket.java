package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 请求只带会话身份和动作；资源及当前库存由服务端解析。 */
public record UnifiedGridActionPacket(int containerId, UUID session, GridResourceKind kind, int epoch,
                                      int serial, Action action, int flags) {
    public enum Action { EXTRACT, SCROLL, INSERT_ITEM, INSERT_FLUID, RESYNC, FILL_FLUID }

    public static void encode(UnifiedGridActionPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.containerId);
        buf.writeUUID(packet.session);
        buf.writeByte(packet.kind.ordinal());
        buf.writeVarInt(packet.epoch);
        buf.writeVarInt(packet.serial);
        buf.writeEnum(packet.action);
        buf.writeByte(packet.flags);
    }

    public static UnifiedGridActionPacket decode(FriendlyByteBuf buf) {
        return new UnifiedGridActionPacket(buf.readVarInt(), buf.readUUID(), GridResourceKind.fromId(buf.readUnsignedByte()),
                buf.readVarInt(), buf.readVarInt(), buf.readEnum(Action.class), buf.readUnsignedByte());
    }

    public static void handle(UnifiedGridActionPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof GridContainerMenu menu)
                    || menu.containerId != packet.containerId || !(menu instanceof UnifiedGridMenuAccess access)) return;
            UnifiedGridSession session = access.rsi$unifiedSession();
            if (session != null) session.handle(packet);
        });
        context.setPacketHandled(true);
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.UNIFIED_GRID_ACTION, UnifiedGridActionPacket.class,
                UnifiedGridActionPacket::encode, UnifiedGridActionPacket::decode, UnifiedGridActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
}
