package com.huanghuang.rsintegration.module.forbidden;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;

public final class FaNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "fa_craft"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    private FaNetworkHandler() {}

    public static void register() {
        if (registered) return;
        CHANNEL.registerMessage(
                0,
                FaCraftPacket.class,
                FaCraftPacket::encode,
                FaCraftPacket::decode,
                FaCraftPacket::handle
        );
        registered = true;
    }

    public static void sendCraft(ResourceLocation ritualId, @Nullable ResourceLocation dim, BlockPos pos) {
        CHANNEL.sendToServer(new FaCraftPacket(ritualId, dim, pos));
    }
}
