package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 仅取消发送玩家的指定预览，不影响已开始执行的订单。 */
public record PlanningCancelPacket(long requestId) {
    public void encode(FriendlyByteBuf buffer) { buffer.writeVarLong(requestId); }
    public static PlanningCancelPacket decode(FriendlyByteBuf buffer) {
        return new PlanningCancelPacket(buffer.readVarLong());
    }
    public static void handle(PlanningCancelPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null && packet.requestId > 0L) {
                GenericCraftPacket.cancelPlanning(context.getSender(), packet.requestId);
            }
        });
        context.setPacketHandled(true);
    }
}
