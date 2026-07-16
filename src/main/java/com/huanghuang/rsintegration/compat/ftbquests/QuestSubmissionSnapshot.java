package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Player/team-specific snapshot of one quest that may be exposed as a submission target. */
public record QuestSubmissionSnapshot(
        long questId,
        String title,
        ItemStack icon,
        boolean repeatable,
        boolean sequential,
        QuestSubmissionEligibility eligibility,
        List<QuestItemRequirement> requirements,
        List<QuestItemRewardPreview> itemRewards,
        int rewardCount,
        boolean hasChoiceReward) {

    public QuestSubmissionSnapshot {
        title = title == null ? "" : title;
        icon = icon.copy();
        requirements = List.copyOf(requirements);
        itemRewards = List.copyOf(itemRewards);
    }

    public boolean eligible() {
        return eligibility == QuestSubmissionEligibility.ELIGIBLE;
    }
}
