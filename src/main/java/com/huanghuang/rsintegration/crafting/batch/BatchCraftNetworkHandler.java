package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class BatchCraftNetworkHandler {

    private static final String PROTOCOL_VERSION = "2";
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
                GenericCraftPacket.class,
                GenericCraftPacket::encode,
                GenericCraftPacket::decode,
                GenericCraftPacket::handle
        );
        CHANNEL.registerMessage(
                1,
                com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket.class,
                com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket::encode,
                com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket::decode,
                com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket::handle
        );
        registered = true;
    }
}
