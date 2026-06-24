package com.huanghuang.rsintegration.mods;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.mods.eidolon.EidolonCraftPacket;
import com.huanghuang.rsintegration.mods.forbidden.FaCraftPacket;
import com.huanghuang.rsintegration.mods.malum.MalumCraftPacket;
import com.huanghuang.rsintegration.mods.wizards_reborn.WRWandCraftPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;

public final class ModCraftNetworkHandlers {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel MALUM = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "malum_craft"),
            () -> PROTOCOL_VERSION, remote -> true, remote -> true);

    public static final SimpleChannel FA = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "fa_craft"),
            () -> PROTOCOL_VERSION, remote -> true, remote -> true);

    public static final SimpleChannel EIDOLON = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "eidolon_craft"),
            () -> PROTOCOL_VERSION, remote -> true, remote -> true);

    public static final SimpleChannel WR_WAND = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "wr_wand"),
            () -> PROTOCOL_VERSION, remote -> true, remote -> true);

    private static boolean malumRegistered;
    private static boolean faRegistered;
    private static boolean eidolonRegistered;
    private static boolean wrWandRegistered;

    private ModCraftNetworkHandlers() {}

    public static void registerMalum() {
        if (malumRegistered) return;
        MALUM.registerMessage(0, MalumCraftPacket.class,
                MalumCraftPacket::encode, MalumCraftPacket::decode, MalumCraftPacket::handle);
        malumRegistered = true;
    }

    public static void registerFa() {
        if (faRegistered) return;
        FA.registerMessage(0, FaCraftPacket.class,
                FaCraftPacket::encode, FaCraftPacket::decode, FaCraftPacket::handle);
        faRegistered = true;
    }

    public static void registerEidolon() {
        if (eidolonRegistered) return;
        EIDOLON.registerMessage(0, EidolonCraftPacket.class,
                EidolonCraftPacket::encode, EidolonCraftPacket::decode, EidolonCraftPacket::handle);
        eidolonRegistered = true;
    }

    public static void registerWRWand() {
        if (wrWandRegistered) return;
        WR_WAND.registerMessage(0, WRWandCraftPacket.class,
                WRWandCraftPacket::encode, WRWandCraftPacket::decode, WRWandCraftPacket::handle);
        wrWandRegistered = true;
    }

    public static void sendMalumCraft(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        MALUM.sendToServer(new MalumCraftPacket(recipeId, dim, pos));
    }

    public static void sendFaCraft(ResourceLocation ritualId, @Nullable ResourceLocation dim, BlockPos pos) {
        FA.sendToServer(new FaCraftPacket(ritualId, dim, pos));
    }

    public static void sendEidolonCraft(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        EIDOLON.sendToServer(new EidolonCraftPacket(recipeId, dim, pos));
    }

    public static void sendWRWandCraft(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        WR_WAND.sendToServer(new WRWandCraftPacket(recipeId, dim, pos));
    }
}
