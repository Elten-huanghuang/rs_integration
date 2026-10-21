package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import net.minecraftforge.common.util.FakePlayer;

import com.huanghuang.rsintegration.machine.FurnaceMachineAccess;
import com.huanghuang.rsintegration.network.gui.MachineInteractionAccess;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import com.huanghuang.rsintegration.util.TrackedNetworkInsertion;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
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
            FurnaceMachineAccess.Result extraction =
                    FurnaceMachineAccess.extractOutput(be);
            if (!extraction.success()) {
                Component error = FurnaceMachineAccess.errorMessage(
                        extraction.error());
                if (error != null) player.sendSystemMessage(error);
                return;
            }
            ItemStack output = extraction.stack();

            if (packet.toRS) {
                INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
                if (network != null) {
                    ItemStack leftover = TrackedNetworkInsertion.insert(network, player, output);
                    InsertedStackDelta.report(player, output, leftover);
                    if (!leftover.isEmpty()) {
                        player.drop(leftover, false);
                    }
                } else {
                    player.drop(output, false);
                }
            } else {
                if (!player.getInventory().add(output)) {
                    player.drop(output, false);
                }
            }

            player.sendSystemMessage(
                Component.translatable("rsi.machine.collected", output.getCount(),
                        Component.translatable(output.getDescriptionId())));
        });
        context.setPacketHandled(true);
    }

}
