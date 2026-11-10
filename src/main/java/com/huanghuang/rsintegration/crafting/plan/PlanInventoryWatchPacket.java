package com.huanghuang.rsintegration.crafting.plan;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端只选择自己已经收到的计划，不能上传材料表或宣称材料足量。 */
public record PlanInventoryWatchPacket(long requestId, long watchId, boolean open) {
    public PlanInventoryWatchPacket {
        if (requestId < 0 || watchId <= 0) throw new IllegalArgumentException("invalid preview subscription");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarLong(requestId);
        buf.writeVarLong(watchId);
        buf.writeBoolean(open);
    }

    public static PlanInventoryWatchPacket decode(FriendlyByteBuf buf) {
        long request = buf.readVarLong();
        long watch = buf.readVarLong();
        boolean open = buf.readBoolean();
        if (request < 0 || watch <= 0 || buf.isReadable()) throw new DecoderException("invalid preview subscription");
        return new PlanInventoryWatchPacket(request, watch, open);
    }

    public static void handle(PlanInventoryWatchPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player != null) PreviewInventorySyncManager.watch(player, packet);
        });
        context.setPacketHandled(true);
    }
}
