package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;

public final class GoetyRSNetworkHandler {

    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static boolean registered;

    private GoetyRSNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkHandler.nextId(), GoetyGuiCheckRSItemsPacket.class,
                GoetyGuiCheckRSItemsPacket::encode, GoetyGuiCheckRSItemsPacket::decode, GoetyGuiCheckRSItemsPacket::handle);
        ch.registerMessage(NetworkHandler.nextId(), GoetyGuiRSItemsResultPacket.class,
                GoetyGuiRSItemsResultPacket::encode, GoetyGuiRSItemsResultPacket::decode, GoetyGuiRSItemsResultPacket::handle);
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
