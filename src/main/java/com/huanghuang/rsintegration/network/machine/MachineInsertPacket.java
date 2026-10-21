package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.machine.MachineSlotType;
import com.huanghuang.rsintegration.machine.FurnaceMachineAccess;
import com.huanghuang.rsintegration.network.gui.MachineInteractionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
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
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        if (player instanceof FakePlayer) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {

            MachineInteractionAccess.Result access =
                    MachineInteractionAccess.resolve(
                            player, packet.dim, packet.pos, true, true);
            if (!access.success()) {
                MachineInteractionAccess.sendError(player, access.error());
                return;
            }

            BlockEntity be = access.target().blockEntity();
            FurnaceMachineAccess.Result insertion =
                    FurnaceMachineAccess.insertCarried(
                            player, be, packet.slot);
            if (!insertion.success()) {
                Component error = FurnaceMachineAccess.errorMessage(
                        insertion.error());
                if (error != null) player.sendSystemMessage(error);
            }
        });
        context.setPacketHandled(true);
    }

}
