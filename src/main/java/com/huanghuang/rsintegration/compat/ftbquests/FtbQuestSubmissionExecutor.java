package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.CustomReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Executes one server-authoritative, consumption-based FTB Quest submission. */
public final class FtbQuestSubmissionExecutor {

    private static final Set<LockKey> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Method CHECK_REPEATABLE = findCheckRepeatable();

    private FtbQuestSubmissionExecutor() {}

    public static boolean submit(ServerPlayer player, long questId, INetwork network) {
        return submit(player, questId,
                network == null ? null : CraftStorageEndpoints.fromLegacyNetwork(network));
    }

    public static boolean submit(ServerPlayer player, long questId,
                               CraftStorageEndpoint endpoint) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        TeamData data = TeamData.get(player);
        if (file == null || file.isLoading() || data == null || data.isLocked()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.not_eligible"));
            return false;
        }
        Quest quest = file.getQuest(questId);
        if (quest == null) return false;
        LockKey key = new LockKey(data.getTeamId(), questId);
        if (!ACTIVE.add(key)) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.busy"));
            return false;
        }
        try {
            final boolean[] completed = {false};
            file.withPlayerContext(player,
                    () -> completed[0] = submitLocked(player, data, quest, endpoint));
            return completed[0];
        } finally {
            ACTIVE.remove(key);
        }
    }

    private static boolean submitLocked(ServerPlayer player, TeamData data, Quest quest,
                                     CraftStorageEndpoint endpoint) {
        INetwork network = endpoint != null && "refinedstorage".equals(
                endpoint.session().reference().backendId().value())
                ? CraftStorageEndpoints.legacyNetwork(endpoint) : null;
        QuestSubmissionSnapshot snapshot = FtbQuestSubmissionScanner.inspect(quest, data, true);
        if (!snapshot.eligible()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.not_eligible"));
            return false;
        }

        Map<Long, ItemTask> tasks = new HashMap<>();
        for (var task : quest.getTasksAsList()) {
            if (task instanceof ItemTask itemTask) tasks.put(FtbQuestObjectId.getId(itemTask), itemTask);
        }

        try (QuestSubmissionEscrow escrow = new QuestSubmissionEscrow(player, endpoint, network)) {
            for (QuestItemRequirement requirement : snapshot.requirements()) {
                ItemTask task = tasks.get(requirement.taskId());
                if (task == null) return false;
                int remaining = Math.toIntExact(requirement.remaining());
                // FTB task filters can encode NBT, tags, and custom predicates
                // that are not represented by the JEI/display item list.
                // Reserve with the task's authoritative matcher, just like the
                // native single-task submit path does.
                Ingredient ingredient = new TaskIngredient(task, requirement.displayStack());
                if (!escrow.reserve(FtbQuestObjectId.getId(task), ingredient, remaining)) {
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-FTBQuests] Escrow reservation failed task={} remaining={} display={}",
                            FtbQuestObjectId.getId(task), remaining, requirement.displayStack());
                    player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.material_changed"));
                    return false;
                }
            }
            if (!escrow.commit()) {
                RSIntegrationMod.LOGGER.warn("[RSI-FTBQuests] Escrow commit failed quest={} requirements={}",
                        FtbQuestObjectId.getId(quest), snapshot.requirements().size());
                player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.material_changed"));
                return false;
            }

            boolean questCompleted;
            try (QuestSubmissionAutoCompletionContext.Scope ignored =
                         QuestSubmissionAutoCompletionContext.open()) {
                for (QuestItemRequirement requirement : snapshot.requirements()) {
                    ItemTask task = tasks.get(requirement.taskId());
                    QuestSubmissionEscrow.Entry entry = escrow.entry(FtbQuestObjectId.getId(task));
                    long before = data.getProgress(task);
                    ItemStack remainder = task.insert(data, entry.stack(), false);
                    long consumed = entry.stack().getCount() - remainder.getCount();
                    long expected = QuestProgressSettlement.expectedAccepted(
                            before, task.getMaxProgress(), entry.stack().getCount());
                    if (expected <= 0L || consumed != expected) {
                        throw new IllegalStateException("FTB Quest task rejected escrowed items: "
                                + FtbQuestObjectId.getId(task));
                    }
                    escrow.settle(entry, remainder);
                }
                questCompleted = data.isCompleted(quest);
            }
            if (!questCompleted) {
                player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.partial"));
                return false;
            }
            ensureCompletionTimestamps(data, quest, tasks.values());
        }
        claimSafeRewards(player, data, quest);
        player.sendSystemMessage(Component.translatable("rsi.ftb_quest.complete", quest.getTitle()));
        return true;
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
        checkRepeatable(quest, data, player.getUUID());
    }

    private static void ensureCompletionTimestamps(TeamData data, Quest quest,
                                                    Iterable<ItemTask> tasks) {
        Date completedAt = new Date();
        for (ItemTask task : tasks) {
            if (data.isCompleted(task)) ensureCompletionTimestamp(data, task, completedAt);
        }
        if (data.isCompleted(quest)) ensureCompletionTimestamp(data, quest, completedAt);
    }

    private static void ensureCompletionTimestamp(TeamData data, Object questObject,
                                                  Date completedAt) {
        long id = FtbQuestObjectId.getId(questObject);
        if (data.getCompletedTime(id).isEmpty()) data.setCompleted(id, completedAt);
    }

    private static Method findCheckRepeatable() {
        try {
            return Quest.class.getMethod("checkRepeatable", TeamData.class, UUID.class);
        } catch (NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /** FTB Quests 2001.4.10/13 return void; 2001.4.20/22 return boolean. */
    private static void checkRepeatable(Quest quest, TeamData data, UUID playerId) {
        try {
            CHECK_REPEATABLE.invoke(quest, data, playerId);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot invoke FTB Quests repeat reset", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("FTB Quests repeat reset failed", cause);
        }
    }

    private record LockKey(UUID teamId, long questId) {}

    private static final class TaskIngredient extends Ingredient {
        private final ItemTask task;

        private TaskIngredient(ItemTask task, ItemStack display) {
            super(Stream.of(new ItemValue(display.copyWithCount(1))));
            this.task = task;
        }

        @Override
        public boolean test(ItemStack stack) {
            return stack != null && task.test(stack);
        }
    }
}
