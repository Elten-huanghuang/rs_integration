package com.huanghuang.rsintegration.mods;

import com.huanghuang.rsintegration.mods.eidolon.EidolonCraftPacket;
import com.huanghuang.rsintegration.mods.forbidden.FaCraftPacket;
import com.huanghuang.rsintegration.mods.malum.MalumCraftPacket;
import com.huanghuang.rsintegration.mods.wizards_reborn.WRWandCraftPacket;
import com.huanghuang.rsintegration.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;

public final class ModCraftNetworkHandlers {

    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static boolean malumRegistered, faRegistered, eidolonRegistered, wrWandRegistered;

    private ModCraftNetworkHandlers() {}

    public static void registerMalum() {
        if (malumRegistered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkHandler.nextId(), MalumCraftPacket.class,
                MalumCraftPacket::encode, MalumCraftPacket::decode, MalumCraftPacket::handle);
        malumRegistered = true;
    }

    public static void registerFa() {
        if (faRegistered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkHandler.nextId(), FaCraftPacket.class,
                FaCraftPacket::encode, FaCraftPacket::decode, FaCraftPacket::handle);
        faRegistered = true;
    }

    public static void registerEidolon() {
        if (eidolonRegistered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkHandler.nextId(), EidolonCraftPacket.class,
                EidolonCraftPacket::encode, EidolonCraftPacket::decode, EidolonCraftPacket::handle);
        eidolonRegistered = true;
    }

    public static void registerWRWand() {
        if (wrWandRegistered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkHandler.nextId(), WRWandCraftPacket.class,
                WRWandCraftPacket::encode, WRWandCraftPacket::decode, WRWandCraftPacket::handle);
        wrWandRegistered = true;
    }

    public static void sendMalumCraft(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        CHANNEL.sendToServer(new MalumCraftPacket(recipeId, dim, pos));
    }

    public static void sendFaCraft(ResourceLocation ritualId, @Nullable ResourceLocation dim, BlockPos pos) {
        CHANNEL.sendToServer(new FaCraftPacket(ritualId, dim, pos));
    }

    public static void sendEidolonCraft(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        CHANNEL.sendToServer(new EidolonCraftPacket(recipeId, dim, pos));
    }

    public static void sendWRWandCraft(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        CHANNEL.sendToServer(new WRWandCraftPacket(recipeId, dim, pos));
    }
}
