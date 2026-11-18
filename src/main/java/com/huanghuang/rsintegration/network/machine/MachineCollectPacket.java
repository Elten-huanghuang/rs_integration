package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.machine.MachineManualActions;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageMachineOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server: collect output from a Quick-type bound machine.
 * ID 7.
 */
public final class MachineCollectPacket {

    private final ResourceLocation dim;
    private final BlockPos pos;
    private final boolean toRS;

    public MachineCollectPacket(ResourceLocation dim, BlockPos pos, boolean toRS) {
        this.dim = dim;
        this.pos = pos;
        this.toRS = toRS;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
        buf.writeBoolean(toRS);
    }

    public static MachineCollectPacket decode(FriendlyByteBuf buf) {
        return new MachineCollectPacket(
            buf.readResourceLocation(),
            buf.readBlockPos(),
            buf.readBoolean()
        );
    }

    public static void handle(MachineCollectPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer player = context.getSender();
        if (player != null && !(player instanceof FakePlayer)) {
            context.enqueueWork(() -> MachineManualActions.collect(
                    player, packet.dim, packet.pos, packet.toRS,
                    MachineManualActions.CollectionPolicy.CURRENT_NETWORK,
                    () -> RefinedStorageMachineOutput.resolve(player)));
        }
        context.setPacketHandled(true);
    }

}
