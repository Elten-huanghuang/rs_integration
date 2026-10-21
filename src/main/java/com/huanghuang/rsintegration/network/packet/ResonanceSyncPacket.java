package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.feature.resonance.bridge.ClientDiskData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;
import java.util.List;

/** 服务器向客户端同步共振盘的资源摘要与弹药快照，供提示和换弹预检查使用。 */
public class ResonanceSyncPacket {

    public final int diskGems;
    public final int lycheeCatalystMask;
    public final int abilityMask;
    public final long revision;
    public final List<ItemStack> taczAmmo;

    public ResonanceSyncPacket(int diskGems) {
        this(diskGems, 0, 0, 0L);
    }

    public ResonanceSyncPacket(int diskGems, int lycheeCatalystMask,
                               int abilityMask, long revision) {
        this(diskGems, lycheeCatalystMask, abilityMask, revision, List.of());
    }

    public ResonanceSyncPacket(int diskGems, int lycheeCatalystMask,
                               int abilityMask, long revision, List<ItemStack> taczAmmo) {
        this.diskGems = diskGems;
        this.lycheeCatalystMask = lycheeCatalystMask;
        this.abilityMask = abilityMask;
        this.revision = revision;
        this.taczAmmo = taczAmmo.stream().map(ItemStack::copy).toList();
    }

    public static void encode(ResonanceSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.diskGems);
        buf.writeVarInt(packet.lycheeCatalystMask);
        buf.writeVarInt(packet.abilityMask);
        buf.writeVarLong(packet.revision);
        buf.writeCollection(packet.taczAmmo, (buffer, stack) -> {
            // 物品包原生数量只有一个字节，单独编码数量以保留旧盘中的大堆叠。
            buffer.writeItem(stack.copyWithCount(1));
            buffer.writeVarInt(stack.getCount());
        });
    }

    public static ResonanceSyncPacket decode(FriendlyByteBuf buf) {
        return new ResonanceSyncPacket(
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong(),
                buf.readList(buffer -> buffer.readItem().copyWithCount(buffer.readVarInt())));
    }

    public static void handle(ResonanceSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientDiskData.apply(
                packet.diskGems, packet.lycheeCatalystMask,
                packet.abilityMask, packet.revision, packet.taczAmmo));
        ctx.get().setPacketHandled(true);
    }
}
