package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.MachineFavoriteKey;
import com.huanghuang.rsintegration.network.binding.MachineFavoritesSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 切换一台已绑定机器是否允许被递归合成自动使用。 */
public record MachineAutomationBlockTogglePacket(MachineFavoriteKey key) {

    public static void encode(MachineAutomationBlockTogglePacket packet, FriendlyByteBuf buf) {
        packet.key.encode(buf);
    }

    public static MachineAutomationBlockTogglePacket decode(FriendlyByteBuf buf) {
        return new MachineAutomationBlockTogglePacket(MachineFavoriteKey.decode(buf));
    }

    public static void handle(MachineAutomationBlockTogglePacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            context.enqueueWork(() -> {
                var binding = AltarBindingRegistry.findBindingEntry(
                        player, packet.key.dimension(), packet.key.pos());
                if (binding == null || !packet.key.blockKey().equals(binding.blockKey())) {
                    return;
                }
                MachineFavoritesSavedData.ToggleResult result =
                        MachineFavoritesSavedData.get(player.server)
                                .toggleBlocked(player.getUUID(), packet.key);
                AltarBindingRegistry.invalidateScanCache();
                player.displayClientMessage(Component.translatable(
                        result == MachineFavoritesSavedData.ToggleResult.ADDED
                                ? "rsi.machine.automation_blocked"
                                : "rsi.machine.automation_enabled"), true);
                MachineNetworkHandler.sendMachineFavoritesSync(player);
            });
        }
        context.setPacketHandled(true);
    }
}
