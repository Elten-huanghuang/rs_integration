package com.huanghuang.rsintegration.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client request to insert the carried item into a BD-bound furnace. */
public final class BeyondDimensionsMachineInsertPacket {
    private final ResourceLocation dim;
    private final BlockPos pos;
    private final MachineSlotType slot;

    public BeyondDimensionsMachineInsertPacket(ResourceLocation dim, BlockPos pos, MachineSlotType slot) {
        this.dim = dim;
        this.pos = pos;
        this.slot = slot;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
        slot.encode(buf);
    }

    public static BeyondDimensionsMachineInsertPacket decode(FriendlyByteBuf buf) {
        return new BeyondDimensionsMachineInsertPacket(
                buf.readResourceLocation(), buf.readBlockPos(), MachineSlotType.decode(buf));
    }

    public static void handle(BeyondDimensionsMachineInsertPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            context.enqueueWork(() -> BeyondDimensionsMachineOperations.insert(
                    player, packet.dim, packet.pos, packet.slot));
        }
        context.setPacketHandled(true);
    }
}
