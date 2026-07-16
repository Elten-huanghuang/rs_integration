package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraftforge.network.simple.SimpleChannel;

public final class BatchCraftNetworkHandler {

    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static boolean registered;

    private BatchCraftNetworkHandler() {}

    public static void register() {
        if (registered) return;
        var ch = NetworkHandler.CHANNEL;
        ch.registerMessage(NetworkPacketIds.GENERIC_CRAFT, GenericCraftPacket.class,
                GenericCraftPacket::encode, GenericCraftPacket::decode, GenericCraftPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.PLAN_RESPONSE, PlanResponsePacket.class,
                PlanResponsePacket::encode, PlanResponsePacket::decode, PlanResponsePacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.CRAFT_STARTED, CraftStartedPacket.class,
                CraftStartedPacket::encode, CraftStartedPacket::decode, CraftStartedPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.CRAFT_PROGRESS, CraftProgressPacket.class,
                CraftProgressPacket::encode, CraftProgressPacket::decode, CraftProgressPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.CRAFT_CANCEL, CraftCancelPacket.class,
                CraftCancelPacket::encode, CraftCancelPacket::decode, CraftCancelPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.CRAFT_STATUS_REQUEST, CraftStatusRequestPacket.class,
                CraftStatusRequestPacket::encode, CraftStatusRequestPacket::decode, CraftStatusRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.CRAFT_STATUS_SYNC, CraftStatusSyncPacket.class,
                CraftStatusSyncPacket::encode, CraftStatusSyncPacket::decode, CraftStatusSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        if (net.minecraftforge.fml.ModList.get().isLoaded(com.huanghuang.rsintegration.util.ModIds.FTB_QUESTS)) {
            ch.registerMessage(NetworkPacketIds.FTB_QUEST_SUBMISSION_REQUEST,
                    com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket.class,
                    com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket::encode,
                    com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket::decode,
                    com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket::handle,
                    java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        }
        registered = true;
    }
}
