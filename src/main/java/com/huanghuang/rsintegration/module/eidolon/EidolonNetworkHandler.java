package com.huanghuang.rsintegration.module.eidolon;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;

public final class EidolonNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "eidolon_craft"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    private EidolonNetworkHandler() {}

    public static void register() {
        if (registered) return;
        CHANNEL.registerMessage(
                0,
                EidolonCraftPacket.class,
                EidolonCraftPacket::encode,
                EidolonCraftPacket::decode,
                EidolonCraftPacket::handle
        );
        registered = true;
    }

    public static void sendCraft(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        CHANNEL.sendToServer(new EidolonCraftPacket(recipeId, dim, pos));
    }
}
