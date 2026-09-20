package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket;
import com.huanghuang.rsintegration.compat.ftbquests.QuestMissingBookmarkPacket;
import com.huanghuang.rsintegration.compat.ftbquests.CheckmarkConfirmPacket;
import com.huanghuang.rsintegration.compat.ftbquests.StorageQuestScanPacket;
import com.huanghuang.rsintegration.compat.ftbquests.InventoryQuestScanPacket;
import com.huanghuang.rsintegration.util.ModIds;

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
        ch.registerMessage(NetworkPacketIds.RECIPE_AVAILABILITY_REQUEST, RecipeAvailabilityRequestPacket.class,
                RecipeAvailabilityRequestPacket::encode, RecipeAvailabilityRequestPacket::decode,
                RecipeAvailabilityRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.RECIPE_AVAILABILITY_RESULT, RecipeAvailabilityResultPacket.class,
                RecipeAvailabilityResultPacket::encode, RecipeAvailabilityResultPacket::decode,
                RecipeAvailabilityResultPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
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
        ch.registerMessage(NetworkPacketIds.CRAFT_PROGRESS_DELTA, CraftProgressDeltaPacket.class,
                CraftProgressDeltaPacket::encode, CraftProgressDeltaPacket::decode,
                CraftProgressDeltaPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.PREPARE_INTERMEDIATE_MATERIALS,
                PrepareIntermediateMaterialsPacket.class,
                PrepareIntermediateMaterialsPacket::encode,
                PrepareIntermediateMaterialsPacket::decode,
                PrepareIntermediateMaterialsPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.CRAFT_CANCEL, CraftCancelPacket.class,
                CraftCancelPacket::encode, CraftCancelPacket::decode, CraftCancelPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.CRAFT_STATUS_REQUEST, CraftStatusRequestPacket.class,
                CraftStatusRequestPacket::encode, CraftStatusRequestPacket::decode, CraftStatusRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.CRAFT_STATUS_SYNC, CraftStatusSyncPacket.class,
                CraftStatusSyncPacket::encode, CraftStatusSyncPacket::decode, CraftStatusSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        if (net.minecraftforge.fml.ModList.get().isLoaded(ModIds.FTB_QUESTS)) {
            ch.registerMessage(NetworkPacketIds.FTB_QUEST_SUBMISSION_REQUEST,
                    QuestSubmissionRequestPacket.class,
                    QuestSubmissionRequestPacket::encode,
                    QuestSubmissionRequestPacket::decode,
                    QuestSubmissionRequestPacket::handle,
                    java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
            ch.registerMessage(NetworkPacketIds.FTB_QUEST_MISSING_BOOKMARK,
                    QuestMissingBookmarkPacket.class,
                    QuestMissingBookmarkPacket::encode,
                    QuestMissingBookmarkPacket::decode,
                    QuestMissingBookmarkPacket::handle,
                    java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
            ch.registerMessage(NetworkPacketIds.FTB_QUEST_CONFIRM_CHECKMARKS, CheckmarkConfirmPacket.class,
                    CheckmarkConfirmPacket::encode, CheckmarkConfirmPacket::decode, CheckmarkConfirmPacket::handle,
                    java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
            ch.registerMessage(NetworkPacketIds.FTB_QUEST_STORAGE_SCAN, StorageQuestScanPacket.class,
                    StorageQuestScanPacket::encode, StorageQuestScanPacket::decode, StorageQuestScanPacket::handle,
                    java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
            ch.registerMessage(NetworkPacketIds.FTB_QUEST_INVENTORY_SCAN, InventoryQuestScanPacket.class,
                    InventoryQuestScanPacket::encode, InventoryQuestScanPacket::decode,
                    InventoryQuestScanPacket::handle,
                    java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        }
        registered = true;
    }
}
