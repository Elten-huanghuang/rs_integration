package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.client.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class AutoEatClientPacketHandler {
    private AutoEatClientPacketHandler() {}

    static void onSync(AutoEatSyncPacket packet) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(packet.message, true);
    }

    static void onBlacklistSync(BlacklistSyncPacket packet) {
        ClientState.blacklistedItems.clear();
        ClientState.blacklistedItems.addAll(packet.blacklist);
        ClientState.blacklistedEffects.clear();
        ClientState.blacklistedEffects.addAll(packet.effectBlacklist);
    }
}
