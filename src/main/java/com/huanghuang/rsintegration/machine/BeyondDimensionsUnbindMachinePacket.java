package com.huanghuang.rsintegration.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client request to remove a machine binding from BD terminal storage. */
public final class BeyondDimensionsUnbindMachinePacket {
    private final ResourceLocation dim;
    private final BlockPos pos;

    public BeyondDimensionsUnbindMachinePacket(ResourceLocation dim, BlockPos pos) {
        this.dim = dim;
        this.pos = pos;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
    }

    public static BeyondDimensionsUnbindMachinePacket decode(FriendlyByteBuf buf) {
        return new BeyondDimensionsUnbindMachinePacket(buf.readResourceLocation(), buf.readBlockPos());
    }

    public static void handle(BeyondDimensionsUnbindMachinePacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            context.enqueueWork(() -> BeyondDimensionsMachineOperations.unbind(
                    player, packet.dim, packet.pos));
        }
        context.setPacketHandled(true);
    }
}
