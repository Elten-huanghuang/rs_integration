package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class GoetyGuiNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "goety_gui"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    private GoetyGuiNetworkHandler() {}

    public static void register() {
        if (registered) return;
        CHANNEL.registerMessage(
                0,
                GoetyGuiSelectRitualPacket.class,
                GoetyGuiSelectRitualPacket::encode,
                GoetyGuiSelectRitualPacket::decode,
                GoetyGuiSelectRitualPacket::handle
        );
        registered = true;
    }

    public static void sendSelectRitual(ResourceLocation recipeId, BlockPos pos) {
        sendSelectRitual(recipeId, null, pos);
    }

    public static void sendSelectRitual(ResourceLocation recipeId, @javax.annotation.Nullable ResourceLocation dim, BlockPos pos) {
        CHANNEL.sendToServer(new GoetyGuiSelectRitualPacket(recipeId, dim, pos));
    }
}
