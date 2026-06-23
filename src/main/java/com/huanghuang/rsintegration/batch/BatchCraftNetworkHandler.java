package com.huanghuang.rsintegration.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class BatchCraftNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "batch_craft"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    private static boolean registered;

    private BatchCraftNetworkHandler() {}

    public static void register() {
        if (registered) return;
        CHANNEL.registerMessage(
                0,
                BatchCraftStartPacket.class,
                BatchCraftStartPacket::encode,
                BatchCraftStartPacket::decode,
                BatchCraftStartPacket::handle
        );
        CHANNEL.registerMessage(
                1,
                GenericCraftPacket.class,
                GenericCraftPacket::encode,
                GenericCraftPacket::decode,
                GenericCraftPacket::handle
        );
        CHANNEL.registerMessage(
                2,
                com.huanghuang.rsintegration.plan.PlanResponsePacket.class,
                com.huanghuang.rsintegration.plan.PlanResponsePacket::encode,
                com.huanghuang.rsintegration.plan.PlanResponsePacket::decode,
                com.huanghuang.rsintegration.plan.PlanResponsePacket::handle
        );
        registered = true;
    }
}
