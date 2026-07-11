package com.huanghuang.rsintegration.resonance.backpack;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.network.gui.GuiOpenRateLimiter;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

public final class OpenResonanceBackpackPacket {

    public OpenResonanceBackpackPacket() {}

    public void encode(FriendlyByteBuf buf) {}

    public static OpenResonanceBackpackPacket decode(FriendlyByteBuf buf) {
        return new OpenResonanceBackpackPacket();
    }

    public static void handle(OpenResonanceBackpackPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player instanceof net.minecraftforge.common.util.FakePlayer) return;

            if (GuiOpenRateLimiter.isRateLimited(player.getUUID())) return;

            INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
            if (network == null) {
                player.sendSystemMessage(Component.translatable("rsi.side_panel.no_network"));
                return;
            }

            ResonanceDiskWrapper wrapper = findResonanceDisk(network);
            if (wrapper == null) {
                player.displayClientMessage(
                        Component.translatable("rsi.resonance_backpack.no_disk"), true);
                return;
            }
            NetworkHooks.openScreen(player,
                    new SimpleMenuProvider(
                            (containerId, inv, p) -> new ResonanceBackpackContainer(
                                    containerId, inv, wrapper),
                            Component.translatable("rsi.resonance_backpack.title")),
                    buf -> {});
        });
        ctx.get().setPacketHandled(true);
    }

    private static ResonanceDiskWrapper findResonanceDisk(INetwork network) {
        return PassiveEffectEngine.findResonanceDisk(network);
    }
}
