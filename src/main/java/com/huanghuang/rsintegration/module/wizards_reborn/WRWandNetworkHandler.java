package com.huanghuang.rsintegration.module.wizards_reborn;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;

public final class WRWandNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "wr_wand"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    private WRWandNetworkHandler() {}

    public static void register() {
        if (registered) return;
        CHANNEL.registerMessage(
                0,
                WRWandCraftPacket.class,
                WRWandCraftPacket::encode,
                WRWandCraftPacket::decode,
                WRWandCraftPacket::handle
        );
        registered = true;
    }

    public static void sendCraft(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        CHANNEL.sendToServer(new WRWandCraftPacket(recipeId, dim, pos));
    }
}
