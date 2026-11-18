package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.machine.MachineManualActions;
import com.huanghuang.rsintegration.machine.MachineSlotType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.common.util.FakePlayer;

import java.util.function.Supplier;

/**
 * Client → Server: insert cursor item into a Quick-type bound machine's input/fuel slot.
 * ID 8.
 */
public final class MachineInsertPacket {

    private final ResourceLocation dim;
    private final BlockPos pos;
    private final MachineSlotType slot;

    public MachineInsertPacket(ResourceLocation dim, BlockPos pos, MachineSlotType slot) {
        this.dim = dim;
        this.pos = pos;
        this.slot = slot;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
        slot.encode(buf);
    }

    public static MachineInsertPacket decode(FriendlyByteBuf buf) {
        return new MachineInsertPacket(
            buf.readResourceLocation(),
            buf.readBlockPos(),
            MachineSlotType.decode(buf)
        );
    }

    public static void handle(MachineInsertPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer player = context.getSender();
        if (player != null && !(player instanceof FakePlayer)) {
            context.enqueueWork(() -> MachineManualActions.insert(player, packet.dim, packet.pos, packet.slot));
        }
        context.setPacketHandled(true);
    }

}
