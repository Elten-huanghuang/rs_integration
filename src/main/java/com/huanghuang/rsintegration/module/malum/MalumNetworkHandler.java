package com.huanghuang.rsintegration.module.malum;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;

public final class MalumNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "malum_craft"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    private MalumNetworkHandler() {}

    public static void register() {
        if (registered) return;
        CHANNEL.registerMessage(
                0,
                MalumCraftPacket.class,
                MalumCraftPacket::encode,
                MalumCraftPacket::decode,
                MalumCraftPacket::handle
        );
        registered = true;
    }

    public static void sendCraft(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        CHANNEL.sendToServer(new MalumCraftPacket(recipeId, dim, pos));
    }
}
