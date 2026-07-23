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

    public boolean contentEquals(QuestSubmissionSnapshot other) {
        if (other == null || questId != other.questId || !title.equals(other.title)
                || repeatable != other.repeatable || sequential != other.sequential
                || eligibility != other.eligibility || rewardCount != other.rewardCount
                || hasChoiceReward != other.hasChoiceReward
                || !ItemStack.matches(icon, other.icon)
                || requirements.size() != other.requirements.size()
                || itemRewards.size() != other.itemRewards.size()) {
            return false;
        }
        for (int i = 0; i < requirements.size(); i++) {
            if (!requirements.get(i).contentEquals(other.requirements.get(i))) return false;
        }
        for (int i = 0; i < itemRewards.size(); i++) {
            if (!itemRewards.get(i).contentEquals(other.itemRewards.get(i))) return false;
        }
        return true;
    }
}
