package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;

/** Stable synthetic IDs used to route quest plans through CraftingPlanScreen. */
public final class QuestSubmissionTargetIds {

    private static final String PREFIX = "ftb_quest_submission/";

    private QuestSubmissionTargetIds() {}

    public static ResourceLocation of(long questId) {
        return new ResourceLocation(RSIntegrationMod.MOD_ID,
                PREFIX + Long.toUnsignedString(questId, 16));
    }

    public static boolean isQuestSubmission(ResourceLocation id) {
        return id != null && RSIntegrationMod.MOD_ID.equals(id.getNamespace())
                && id.getPath().startsWith(PREFIX);
    }

    public static long questId(ResourceLocation id) {
        if (!isQuestSubmission(id)) throw new IllegalArgumentException("not a quest submission id: " + id);
        return Long.parseUnsignedLong(id.getPath().substring(PREFIX.length()), 16);
    }
}
