package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket;
import com.huanghuang.rsintegration.compat.ftbquests.QuestMissingBookmarkPacket;
import com.huanghuang.rsintegration.compat.ftbquests.CheckmarkConfirmPacket;
import com.huanghuang.rsintegration.compat.ftbquests.StorageQuestScanPacket;
import com.huanghuang.rsintegration.util.ModIds;
import java.util.Optional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkDirection;

import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressPacket;
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
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.RECIPE_AVAILABILITY_RESULT, RecipeAvailabilityResultPacket.class,
                RecipeAvailabilityResultPacket::encode, RecipeAvailabilityResultPacket::decode,
                RecipeAvailabilityResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.GENERIC_CRAFT, GenericCraftPacket.class,
                GenericCraftPacket::encode, GenericCraftPacket::decode, GenericCraftPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.PLAN_RESPONSE, PlanResponsePacket.class,
                PlanResponsePacket::encode, PlanResponsePacket::decode, PlanResponsePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.CRAFT_STARTED, CraftStartedPacket.class,
                CraftStartedPacket::encode, CraftStartedPacket::decode, CraftStartedPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.CRAFT_PROGRESS, CraftProgressPacket.class,
                CraftProgressPacket::encode, CraftProgressPacket::decode, CraftProgressPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.PLANNING_PROGRESS, PlanningProgressPacket.class,
                PlanningProgressPacket::encode, PlanningProgressPacket::decode,
                PlanningProgressPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.CRAFT_PROGRESS_DELTA, CraftProgressDeltaPacket.class,
                CraftProgressDeltaPacket::encode, CraftProgressDeltaPacket::decode,
                CraftProgressDeltaPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(NetworkPacketIds.PREPARE_INTERMEDIATE_MATERIALS,
                PrepareIntermediateMaterialsPacket.class,
                PrepareIntermediateMaterialsPacket::encode,
                PrepareIntermediateMaterialsPacket::decode,
                PrepareIntermediateMaterialsPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.CRAFT_CANCEL, CraftCancelPacket.class,
                CraftCancelPacket::encode, CraftCancelPacket::decode, CraftCancelPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.CRAFT_STATUS_REQUEST, CraftStatusRequestPacket.class,
                CraftStatusRequestPacket::encode, CraftStatusRequestPacket::decode, CraftStatusRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        ch.registerMessage(NetworkPacketIds.CRAFT_STATUS_SYNC, CraftStatusSyncPacket.class,
                CraftStatusSyncPacket::encode, CraftStatusSyncPacket::decode, CraftStatusSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        if (ModList.get().isLoaded(ModIds.FTB_QUESTS)) {
            ch.registerMessage(NetworkPacketIds.FTB_QUEST_SUBMISSION_REQUEST,
                    QuestSubmissionRequestPacket.class,
                    QuestSubmissionRequestPacket::encode,
                    QuestSubmissionRequestPacket::decode,
                    QuestSubmissionRequestPacket::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
            ch.registerMessage(NetworkPacketIds.FTB_QUEST_MISSING_BOOKMARK,
                    QuestMissingBookmarkPacket.class,
                    QuestMissingBookmarkPacket::encode,
                    QuestMissingBookmarkPacket::decode,
                    QuestMissingBookmarkPacket::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            ch.registerMessage(NetworkPacketIds.FTB_QUEST_CONFIRM_CHECKMARKS, CheckmarkConfirmPacket.class,
                    CheckmarkConfirmPacket::encode, CheckmarkConfirmPacket::decode, CheckmarkConfirmPacket::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
            ch.registerMessage(NetworkPacketIds.FTB_QUEST_STORAGE_SCAN, StorageQuestScanPacket.class,
                    StorageQuestScanPacket::encode, StorageQuestScanPacket::decode, StorageQuestScanPacket::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
        }
        registered = true;
    }
}
