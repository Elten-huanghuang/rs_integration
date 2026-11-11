package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorNavigationServer.Result;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 按请求编号反馈导航结果，迟到响应不能把已关闭的任务中心重新打开。 */
public record RsMonitorNavigationResultPacket(long requestId, Result result) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(requestId);
        buffer.writeEnum(result);
    }

    public static RsMonitorNavigationResultPacket decode(FriendlyByteBuf buffer) {
        return new RsMonitorNavigationResultPacket(buffer.readVarLong(), buffer.readEnum(Result.class));
    }

    public static void handle(RsMonitorNavigationResultPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> RsMonitorNavigationClient.accept(packet)));
        context.setPacketHandled(true);
    }
}
