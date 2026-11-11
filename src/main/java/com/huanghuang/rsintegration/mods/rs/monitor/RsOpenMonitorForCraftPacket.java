package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

/** 从任务中心请求原生监控器：运行订单由服务端取网络，待确认计划按当前访问权校验。 */
public record RsOpenMonitorForCraftPacket(long requestId, @Nullable StorageReference network,
                                          @Nullable UUID craftId) {
    public RsOpenMonitorForCraftPacket {
        if (requestId <= 0 || network != null && craftId != null) throw new IllegalArgumentException("invalid monitor request");
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(requestId);
        buffer.writeBoolean(network != null);
        if (network != null) {
            buffer.writeUtf(network.backendId().value(), StorageBackendId.MAX_LENGTH);
            buffer.writeUtf(network.networkId(), StorageReference.MAX_NETWORK_ID_LENGTH);
        }
        buffer.writeBoolean(craftId != null);
        if (craftId != null) buffer.writeUUID(craftId);
    }

    public static RsOpenMonitorForCraftPacket decode(FriendlyByteBuf buffer) {
        long request = buffer.readVarLong();
        StorageReference network = buffer.readBoolean() ? new StorageReference(
                new StorageBackendId(buffer.readUtf(StorageBackendId.MAX_LENGTH)),
                buffer.readUtf(StorageReference.MAX_NETWORK_ID_LENGTH)) : null;
        return new RsOpenMonitorForCraftPacket(request, network, buffer.readBoolean() ? buffer.readUUID() : null);
    }

    public static void handle(RsOpenMonitorForCraftPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            var result = RsMonitorNavigationServer.openForTask(player, packet);
            BatchCraftNetworkHandler.CHANNEL.sendTo(new RsMonitorNavigationResultPacket(packet.requestId(), result),
                    player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        });
        context.setPacketHandled(true);
    }
}
