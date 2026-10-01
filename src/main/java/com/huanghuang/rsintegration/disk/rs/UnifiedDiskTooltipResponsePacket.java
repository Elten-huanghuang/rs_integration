package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.core.UnifiedDiskSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** 摘要通过客户端专用分发器进入缓存，不在服务器注册阶段链接屏幕类型。 */
public record UnifiedDiskTooltipResponsePacket(UUID world, UUID disk, UnifiedDiskSummary summary) {
    public static void encode(UnifiedDiskTooltipResponsePacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.world); buf.writeUUID(packet.disk); buf.writeBoolean(packet.summary != null);
        if (packet.summary == null) return;
        UnifiedDiskSummary summary = packet.summary;
        buf.writeVarLong(summary.items()); buf.writeVarLong(summary.fluids());
        buf.writeVarInt(summary.itemTypes()); buf.writeVarInt(summary.fluidTypes());
        buf.writeVarInt(summary.itemCapacity()); buf.writeVarInt(summary.fluidCapacity());
    }
    public static UnifiedDiskTooltipResponsePacket decode(FriendlyByteBuf buf) {
        UUID world = buf.readUUID(), disk = buf.readUUID();
        return new UnifiedDiskTooltipResponsePacket(world, disk, buf.readBoolean() ? new UnifiedDiskSummary(buf.readVarLong(),
                buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()) : null);
    }
    public static void handle(UnifiedDiskTooltipResponsePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> UnifiedDiskTooltipClientPacketHandler.handle(packet)));
        context.setPacketHandled(true);
    }
}
