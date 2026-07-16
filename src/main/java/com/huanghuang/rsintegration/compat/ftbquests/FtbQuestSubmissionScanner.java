package com.huanghuang.rsintegration.compat.ftbquests;

import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Scans FTB Quests into immutable recursive-submission snapshots. */
public final class FtbQuestSubmissionScanner {

    private FtbQuestSubmissionScanner() {}

    public static List<QuestSubmissionSnapshot> scanServer(ServerPlayer player) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || file.isLoading()) return List.of();
        TeamData data = TeamData.get(player);
        if (data == null || data.isLocked()) return List.of();
        return scan(file, data, true);
    }

    public static List<QuestSubmissionSnapshot> scan(BaseQuestFile file, TeamData data,
                                                      boolean requireVisibility) {
        if (file == null || file.isLoading() || data == null || data.isLocked()) return List.of();
        List<QuestSubmissionSnapshot> result = new ArrayList<>();
        file.forAllQuests(quest -> {
            QuestSubmissionSnapshot snapshot = inspect(quest, data, requireVisibility);
            if (snapshot.eligible()) result.add(snapshot);
        });
        result.sort(Comparator.comparing(QuestSubmissionSnapshot::title)
                .thenComparingLong(QuestSubmissionSnapshot::questId));
        return List.copyOf(result);
    }

    @Nullable
    public static QuestSubmissionSnapshot findServer(ServerPlayer player, long questId) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || file.isLoading()) return null;
        TeamData data = TeamData.get(player);
        if (data == null || data.isLocked()) return null;
        Quest quest = file.getQuest(questId);
        return quest == null ? null : inspect(quest, data, true);
    }

    public static QuestSubmissionSnapshot inspect(Quest quest, TeamData data,
                                                   boolean requireVisibility) {
        List<QuestItemRequirement> requirements = new ArrayList<>();
        QuestSubmissionEligibility eligibility = baseEligibility(quest, data, requireVisibility);

        if (eligibility == QuestSubmissionEligibility.ELIGIBLE) {
            for (Task task : quest.getTasksAsList()) {
                if (data.isCompleted(task)) continue;
                if (!(task instanceof ItemTask itemTask)) {
                    if (!task.isOptionalForProgression()) {
                        eligibility = QuestSubmissionEligibility.NON_ITEM_TASK_REQUIRED;
                        break;
                    }
                    continue;
                }

                QuestSubmissionEligibility taskEligibility = inspectTask(itemTask);
                if (taskEligibility != QuestSubmissionEligibility.ELIGIBLE) {
                    eligibility = taskEligibility;
                    break;
                }

                long required = itemTask.getMaxProgress();
                long progress = data.getProgress(itemTask);
                if (progress >= required) continue;
                List<ItemStack> valid = itemTask.getValidDisplayItems();
                ItemStack display = itemTask.getItemStack();
                if (display.isEmpty() && !valid.isEmpty()) display = valid.get(0);
                requirements.add(new QuestItemRequirement(itemTask.getId(), display, valid,
                        required, progress, true, false, false,
                        itemTask.isOptionalForProgression()));
            }
            if (eligibility == QuestSubmissionEligibility.ELIGIBLE && requirements.isEmpty()) {
                eligibility = QuestSubmissionEligibility.NO_REMAINING_ITEM_TASKS;
            }
        }

        ItemStack icon = requirements.isEmpty()
                ? ItemStack.EMPTY : requirements.get(0).displayStack().copy();
        List<QuestItemRewardPreview> itemRewards = quest.getRewards().stream()
                .filter(ItemReward.class::isInstance)
                .map(ItemReward.class::cast)
                .filter(reward -> !reward.getItem().isEmpty())
                .map(reward -> new QuestItemRewardPreview(reward.getId(),
                        reward.getItem().copyWithCount(Math.max(1, reward.getCount()))))
                .toList();
        boolean hasChoice = quest.getRewards().stream().anyMatch(ChoiceReward.class::isInstance);
        return new QuestSubmissionSnapshot(quest.getId(), quest.getTitle().getString(), icon,
                quest.canBeRepeated(), quest.getRequireSequentialTasks(), eligibility,
                requirements, itemRewards, quest.getRewards().size(), hasChoice);
    }

    private static QuestSubmissionEligibility baseEligibility(Quest quest, TeamData data,
                                                               boolean requireVisibility) {
        if (data.isLocked()) return QuestSubmissionEligibility.TEAM_DATA_LOCKED;
        if (data.isCompleted(quest) && !quest.canBeRepeated()) {
            return QuestSubmissionEligibility.QUEST_ALREADY_COMPLETED;
        }
        if (!data.canStartTasks(quest)) return QuestSubmissionEligibility.QUEST_NOT_STARTABLE;
        if (requireVisibility && !quest.isVisible(data)) return QuestSubmissionEligibility.QUEST_HIDDEN;
        if (quest.getRequireSequentialTasks()) return QuestSubmissionEligibility.SEQUENTIAL_TASKS_UNSUPPORTED;
        return QuestSubmissionEligibility.ELIGIBLE;
    }

    static QuestSubmissionEligibility inspectTask(ItemTask task) {
        if (task.isTaskScreenOnly()) return QuestSubmissionEligibility.TASK_SCREEN_ONLY;
        if (task.isOnlyFromCrafting()) return QuestSubmissionEligibility.CRAFTING_ONLY_TASK_UNSUPPORTED;
        if (!task.consumesResources()) return QuestSubmissionEligibility.NON_CONSUMING_TASK_UNSUPPORTED;
        if (task.getMaxProgress() <= 0L || task.getItemStack().isEmpty()
                && task.getValidDisplayItems().isEmpty()) {
            return QuestSubmissionEligibility.INVALID_ITEM_TASK;
        }
        return QuestSubmissionEligibility.ELIGIBLE;
    }
}
