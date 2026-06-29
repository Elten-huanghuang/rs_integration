package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.simple.SimpleChannel;

public final class GoetyGuiNetworkHandler {

    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static boolean registered;

    private GoetyGuiNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkHandler.nextId(), GoetyGuiSelectRitualPacket.class,
                GoetyGuiSelectRitualPacket::encode, GoetyGuiSelectRitualPacket::decode, GoetyGuiSelectRitualPacket::handle);
        registered = true;
    }

    public static void sendSelectRitual(ResourceLocation recipeId, BlockPos pos) {
        sendSelectRitual(recipeId, null, pos);
    }

    public static void sendSelectRitual(ResourceLocation recipeId, @javax.annotation.Nullable ResourceLocation dim, BlockPos pos) {
        CHANNEL.sendToServer(new GoetyGuiSelectRitualPacket(recipeId, dim, pos));
    }
}
