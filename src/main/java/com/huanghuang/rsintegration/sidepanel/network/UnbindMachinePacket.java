package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server request to remove one machine from the player's connectors. */
public final class UnbindMachinePacket {

    private final ResourceLocation dim;
    private final BlockPos pos;

    public UnbindMachinePacket(ResourceLocation dim, BlockPos pos) {
        this.dim = dim;
        this.pos = pos;
    }

    ResourceLocation dim() {
        return dim;
    }

    BlockPos pos() {
        return pos;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
    }

    public static UnbindMachinePacket decode(FriendlyByteBuf buf) {
        return new UnbindMachinePacket(buf.readResourceLocation(), buf.readBlockPos());
    }

    public static void handle(UnbindMachinePacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer player = context.getSender();
        if (player == null || player instanceof FakePlayer) {
            context.setPacketHandled(true);
            return;
        }

        context.enqueueWork(() -> unbind(player, packet.dim, packet.pos));
        context.setPacketHandled(true);
    }

    private static void unbind(ServerPlayer player, ResourceLocation dim, BlockPos pos) {
        BindingStorage.BindingEntry entry = AltarBindingRegistry.findBindingEntry(player, dim, pos);
        if (entry == null) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.not_bound"));
            RSSidePanelNetworkHandler.sendBindingSync(player);
            return;
        }

        int removed = AltarBindingRegistry.removePlayerBinding(player, dim, pos);
        if (removed == 0) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.not_bound"));
            RSSidePanelNetworkHandler.sendBindingSync(player);
            return;
        }

        Component name = BindingEventHandler.resolveBlockName(
                entry.blockKey(), entry.blockRegKey(), entry.displayStack());
        player.sendSystemMessage(Component.translatable("gui.rs_integration.altar.unbound", name));
        RSSidePanelNetworkHandler.sendBindingSync(player);
        RSIntegrationMod.LOGGER.debug(
                "[RSI-MachineHub] Player {} removed binding dim={} pos={} connectors={}",
                player.getScoreboardName(), dim, pos, removed);
    }
}
