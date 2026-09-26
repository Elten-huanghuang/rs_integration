package com.huanghuang.rsintegration.mods;

import com.huanghuang.rsintegration.mods.eidolon.EidolonCraftPacket;
import com.huanghuang.rsintegration.mods.forbidden.FaCraftPacket;
import com.huanghuang.rsintegration.mods.malum.MalumCraftPacket;
import com.huanghuang.rsintegration.mods.wizardsreborn.WRWandCraftPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import java.util.Optional;
import net.minecraftforge.network.NetworkDirection;

public final class ModCraftNetworkHandlers {

    private static boolean malumRegistered, faRegistered, eidolonRegistered, wrWandRegistered;

    private ModCraftNetworkHandlers() {}

    public static void registerMalum() {
        if (malumRegistered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.MALUM_CRAFT, MalumCraftPacket.class,
                MalumCraftPacket::encode, MalumCraftPacket::decode, MalumCraftPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        malumRegistered = true;
    }

    public static void registerFa() {
        if (faRegistered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.FA_CRAFT, FaCraftPacket.class,
                FaCraftPacket::encode, FaCraftPacket::decode, FaCraftPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        faRegistered = true;
    }

    public static void registerEidolon() {
        if (eidolonRegistered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.EIDOLON_CRAFT, EidolonCraftPacket.class,
                EidolonCraftPacket::encode, EidolonCraftPacket::decode, EidolonCraftPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        eidolonRegistered = true;
    }

    public static void registerWRWand() {
        if (wrWandRegistered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.WR_WAND_CRAFT, WRWandCraftPacket.class,
                WRWandCraftPacket::encode, WRWandCraftPacket::decode, WRWandCraftPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        wrWandRegistered = true;
    }

    public static void registerArsNouveau() {
        // Ars Nouveau uses the GenericCraftPacket system via IBatchDelegate
        // No custom packet registration needed
    }

}
