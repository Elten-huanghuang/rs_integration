package com.huanghuang.rsintegration.transfer;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ContainerTransferNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "container_transfer"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    private ContainerTransferNetworkHandler() {}

    public static void register() {
        if (registered) return;
        CHANNEL.registerMessage(
                0,
                StoreAllPacket.class,
                StoreAllPacket::encode,
                StoreAllPacket::decode,
                StoreAllPacket::handle
        );
        registered = true;
    }
}
