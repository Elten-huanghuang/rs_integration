package com.huanghuang.rsintegration.compat.ftbquests;

import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.CustomReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Executes one server-authoritative, consumption-based FTB Quest submission. */
public final class FtbQuestSubmissionExecutor {

    private static final Set<LockKey> ACTIVE = ConcurrentHashMap.newKeySet();

    private FtbQuestSubmissionExecutor() {}

    public static void submit(ServerPlayer player, long questId, INetwork network) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        TeamData data = TeamData.get(player);
        if (file == null || file.isLoading() || data == null || data.isLocked()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.not_eligible"));
            return;
        }
        Quest quest = file.getQuest(questId);
        if (quest == null) return;
        LockKey key = new LockKey(data.getTeamId(), questId);
        if (!ACTIVE.add(key)) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.busy"));
            return;
        }
        try {
            file.withPlayerContext(player, () -> submitLocked(player, data, quest, network));
        } finally {
            ACTIVE.remove(key);
        }
    }

    private static void submitLocked(ServerPlayer player, TeamData data, Quest quest,
                                     INetwork network) {
        QuestSubmissionSnapshot snapshot = FtbQuestSubmissionScanner.inspect(quest, data, true);
        if (!snapshot.eligible()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.not_eligible"));
            return;
        }

        Map<Long, ItemTask> tasks = new HashMap<>();
        for (var task : quest.getTasksAsList()) {
            if (task instanceof ItemTask itemTask) tasks.put(itemTask.getId(), itemTask);
        }

        try (QuestSubmissionEscrow escrow = new QuestSubmissionEscrow(player, network)) {
            for (QuestItemRequirement requirement : snapshot.requirements()) {
                ItemTask task = tasks.get(requirement.taskId());
                if (task == null) return;
                int remaining = Math.toIntExact(requirement.remaining());
                Ingredient ingredient = requirement.validDisplayItems().isEmpty()
                        ? Ingredient.of(requirement.displayStack())
                        : Ingredient.of(requirement.validDisplayItems().stream());
                if (!escrow.reserve(task.getId(), ingredient, remaining)) {
                    player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.material_changed"));
                    return;
                }
            }
            if (!escrow.commit()) {
                player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.material_changed"));
                return;
            }

            for (QuestItemRequirement requirement : snapshot.requirements()) {
                ItemTask task = tasks.get(requirement.taskId());
                QuestSubmissionEscrow.Entry entry = escrow.entry(task.getId());
                long before = data.getProgress(task);
                ItemStack remainder = task.insert(data, entry.stack(), false);
                long accepted = data.getProgress(task) - before;
                long consumed = entry.stack().getCount() - remainder.getCount();
                if (accepted <= 0L || consumed != accepted) {
                    throw new IllegalStateException("FTB Quest task rejected escrowed items: " + task.getId());
                }
                escrow.settle(entry, remainder);
            }
        }

        if (!data.isCompleted(quest)) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.partial"));
            return;
        }
        claimSafeRewards(player, data, quest);
        player.sendSystemMessage(Component.translatable("rsi.ftb_quest.complete", quest.getTitle()));
    }

    private static ItemStack submitEntry(ServerQuestFile file, ItemTask task, TeamData data,
                                          ServerPlayer player, ItemStack stack) {
        final ItemStack[] remainder = {stack.copy()};
        file.withPlayerContext(player,
                () -> task.submitTask(data, player, remainder[0]));
        return remainder[0];
    }

    private static void claimSafeRewards(ServerPlayer player, TeamData data, Quest quest) {
        for (Reward reward : quest.getRewards()) {
            if (reward instanceof ChoiceReward || reward instanceof CustomReward) continue;
            if (data.isRewardBlocked(reward)) continue;
            if (!data.getClaimType(player.getUUID(), reward).canClaim()) continue;
            data.claimReward(player, reward, true);
        }
        quest.checkRepeatable(data, player.getUUID());
    }

    private record LockKey(UUID teamId, long questId) {}
}
