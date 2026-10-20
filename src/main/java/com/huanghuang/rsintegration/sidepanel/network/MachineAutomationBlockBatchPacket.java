package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoriteKey;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoritesSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 框选后批量修改机器的自动合成黑名单状态。 */
public record MachineAutomationBlockBatchPacket(List<MachineFavoriteKey> keys,
                                                boolean blocked) {
    public MachineAutomationBlockBatchPacket {
        keys = List.copyOf(keys);
        if (keys.size() > MachineFavoritesSavedData.MAX_BLOCKED) {
            throw new IllegalArgumentException("Too many machine blocklist updates");
        }
    }

    public static void encode(MachineAutomationBlockBatchPacket packet,
                              FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.keys.size());
        for (MachineFavoriteKey key : packet.keys) key.encode(buffer);
        buffer.writeBoolean(packet.blocked);
    }

    public static MachineAutomationBlockBatchPacket decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MachineFavoritesSavedData.MAX_BLOCKED) {
            throw new IllegalArgumentException("Invalid machine blocklist batch size");
        }
        List<MachineFavoriteKey> keys = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            keys.add(MachineFavoriteKey.decode(buffer));
        }
        return new MachineAutomationBlockBatchPacket(keys, buffer.readBoolean());
    }

    public static void handle(MachineAutomationBlockBatchPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            context.enqueueWork(() -> {
                Set<MachineFavoriteKey> valid = new LinkedHashSet<>();
                for (MachineFavoriteKey key : packet.keys) {
                    var entry = AltarBindingRegistry.findBindingEntry(
                            player, key.dimension(), key.pos());
                    if (entry != null && key.blockKey().equals(entry.blockKey())) {
                        valid.add(key);
                    }
                }
                if (valid.isEmpty()) {
                    RSSidePanelNetworkHandler.sendMachineFavoritesSync(player);
                    return;
                }
                int changed = MachineFavoritesSavedData.get(player.server)
                        .setBlocked(player.getUUID(), valid, packet.blocked);
                AltarBindingRegistry.invalidateScanCache();
                if (changed > 0) {
                    player.displayClientMessage(Component.translatable(
                            packet.blocked
                                    ? "rsi.machine.automation_batch_blocked"
                                    : "rsi.machine.automation_batch_enabled",
                            changed), true);
                }
                RSSidePanelNetworkHandler.sendMachineFavoritesSync(player);
            });
        }
        context.setPacketHandled(true);
    }
}
