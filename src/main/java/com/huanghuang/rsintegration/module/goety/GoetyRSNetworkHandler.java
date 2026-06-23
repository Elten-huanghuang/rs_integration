package com.huanghuang.rsintegration.module.goety;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;

public final class GoetyRSNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "goety_rs"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    private GoetyRSNetworkHandler() {}

    public static void register() {
        if (registered) return;
        CHANNEL.registerMessage(
                0,
                GoetyGuiCheckRSItemsPacket.class,
                GoetyGuiCheckRSItemsPacket::encode,
                GoetyGuiCheckRSItemsPacket::decode,
                GoetyGuiCheckRSItemsPacket::handle
        );
        CHANNEL.registerMessage(
                1,
                GoetyGuiRSItemsResultPacket.class,
                GoetyGuiRSItemsResultPacket::encode,
                GoetyGuiRSItemsResultPacket::decode,
                GoetyGuiRSItemsResultPacket::handle
        );
        registered = true;
    }

    public static void sendCheckRS(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos altarPos) {
        CHANNEL.sendToServer(new GoetyGuiCheckRSItemsPacket(recipeId, dim, altarPos));
    }

    public static void sendRSResult(ServerPlayer player, ResourceLocation recipeId, boolean[] results) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GoetyGuiRSItemsResultPacket(recipeId, results));
    }
}
