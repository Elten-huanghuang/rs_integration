package com.huanghuang.rsintegration.mods;

import com.huanghuang.rsintegration.mods.eidolon.EidolonCraftPacket;
import com.huanghuang.rsintegration.mods.forbidden.FaCraftPacket;
import com.huanghuang.rsintegration.mods.malum.MalumCraftPacket;
import com.huanghuang.rsintegration.mods.wizards_reborn.WRWandCraftPacket;
import com.huanghuang.rsintegration.network.NetworkHandler;

public final class ModCraftNetworkHandlers {

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

}
