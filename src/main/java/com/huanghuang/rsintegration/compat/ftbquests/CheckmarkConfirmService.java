package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mixin.ftbquests.ItemTaskSequenceAccessor;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.CommandReward;
import dev.ftb.mods.ftbquests.quest.task.CheckmarkTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/** Completes only checkmark tasks which FTB Quests currently allows the player to submit. */
public final class CheckmarkConfirmService {

    private static final long REQUEST_COOLDOWN_TICKS = 10L;
    private static final int TASKS_PER_TEAM_PER_TICK = 2;
    private static final int TASKS_PER_SERVER_PER_TICK = 8;
    private static final Map<UUID, Long> LAST_TEAM_REQUEST = new HashMap<>();
    private static final Map<UUID, ConfirmationJob> ACTIVE_JOBS = new HashMap<>();

    private CheckmarkConfirmService() {
    }

    public static void confirmAvailable(ServerPlayer player) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        TeamData data = TeamData.get(player);
        if (file == null || file.isLoading() || data == null || data.isLocked()) return;

        long now = player.serverLevel().getGameTime();
        UUID teamId = data.getTeamId();
        if (ACTIVE_JOBS.containsKey(teamId)) return;
        LAST_TEAM_REQUEST.entrySet().removeIf(entry -> now < entry.getValue()
                || now - entry.getValue() > 1_200L);
        Long previous = LAST_TEAM_REQUEST.get(teamId);
        if (previous != null && now >= previous && now - previous < REQUEST_COOLDOWN_TICKS) return;
        LAST_TEAM_REQUEST.put(teamId, now);

        file.withPlayerContext(player, () -> enqueueSnapshot(player, file, data));
    }

    private static void enqueueSnapshot(ServerPlayer player, ServerQuestFile file, TeamData data) {
        List<Long> initiallyAvailable = new ArrayList<>();
        Set<Long> blacklist = readBlacklist();
        file.forAllQuests(quest -> collectAvailable(
                player, data, quest, blacklist, initiallyAvailable));

        if (initiallyAvailable.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.checkmarks.none"));
            return;
        }
        ACTIVE_JOBS.put(data.getTeamId(), new ConfirmationJob(
                player.getUUID(), List.copyOf(initiallyAvailable)));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE_JOBS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        int remainingServerBudget = TASKS_PER_SERVER_PER_TICK;

        Iterator<Map.Entry<UUID, ConfirmationJob>> iterator = ACTIVE_JOBS.entrySet().iterator();
        while (iterator.hasNext() && remainingServerBudget > 0) {
            Map.Entry<UUID, ConfirmationJob> entry = iterator.next();
            ConfirmationJob job = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(job.playerId);
            if (player == null) {
                iterator.remove();
                continue;
            }

            int allowance = Math.min(TASKS_PER_TEAM_PER_TICK, remainingServerBudget);
            try {
                int attempted = processBatch(player, entry.getKey(), job, allowance);
                remainingServerBudget -= attempted;
            } catch (RuntimeException | LinkageError exception) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Failed to process bulk checkmark confirmation for team {}",
                        entry.getKey(), exception);
                iterator.remove();
                continue;
            }

            if (job.finished()) {
                iterator.remove();
                sendResult(player, job.confirmed);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE_JOBS.clear();
        LAST_TEAM_REQUEST.clear();
    }

    private static int processBatch(ServerPlayer player, UUID expectedTeamId,
                                    ConfirmationJob job, int allowance) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        TeamData data = TeamData.get(player);
        if (file == null || file.isLoading() || data == null || data.isLocked()
                || !expectedTeamId.equals(data.getTeamId())) {
            job.cancel();
            return 0;
        }

        int startCursor = job.cursor;
        file.withPlayerContext(player, () -> {
            int end = Math.min(job.taskIds.size(), job.cursor + allowance);
            while (job.cursor < end) {
                long taskId = job.nextTaskId();
                Task task = file.getTask(taskId);
                if (task instanceof CheckmarkTask checkmark
                        && isAvailable(player, data, checkmark)) {
                    checkmark.submitTask(data, player);
                    job.confirmed++;
                }
            }
        });
        return job.cursor - startCursor;
    }

    private static void sendResult(ServerPlayer player, int confirmed) {
        if (confirmed == 0) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.checkmarks.none"));
        } else {
            player.sendSystemMessage(Component.translatable(
                    "rsi.ftb_quest.checkmarks.confirmed", confirmed));
        }
    }

    private static void collectAvailable(ServerPlayer player, TeamData data, Quest quest,
                                         Set<Long> blacklist, List<Long> result) {
        if (blacklist.contains(FtbQuestObjectId.getId(quest))) return;
        if (quest.getRewards().stream().anyMatch(CommandReward.class::isInstance)) return;
        if (!quest.isVisible(data) || !data.canStartTasks(quest)) return;
        for (Task task : quest.getTasksAsList()) {
            if (task instanceof CheckmarkTask checkmark
                    && isAvailable(player, data, checkmark)) {
                result.add(FtbQuestObjectId.getId(checkmark));
            }
        }
    }

    private static Set<Long> readBlacklist() {
        Set<Long> result = new HashSet<>();
        for (String configuredId : RSIntegrationConfig.FTB_QUEST_CHECKMARK_BLACKLIST.get()) {
            try {
                result.add(Long.parseUnsignedLong(configuredId, 16));
            } catch (NumberFormatException exception) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Ignoring invalid checkmark blacklist ID: {}",
                        configuredId);
            }
        }
        return result;
    }

    private static boolean isAvailable(ServerPlayer player, TeamData data,
                                       CheckmarkTask checkmark) {
        Quest quest = checkmark.getQuest();
        return !data.isCompleted(checkmark)
                && quest.getChapter().isVisible(data)
                && quest.isVisible(data)
                && data.canStartTasks(quest)
                && ((ItemTaskSequenceAccessor) (Object) checkmark).rsi$checkTaskSequence(data)
                && checkmark.canSubmit(data, player);
    }

    private static final class ConfirmationJob {
        private final UUID playerId;
        private final List<Long> taskIds;
        private int cursor;
        private int confirmed;

        private ConfirmationJob(UUID playerId, List<Long> taskIds) {
            this.playerId = playerId;
            this.taskIds = taskIds;
        }

        private long nextTaskId() {
            return taskIds.get(cursor++);
        }

        private boolean finished() {
            return cursor >= taskIds.size();
        }

        private void cancel() {
            cursor = taskIds.size();
        }
    }
}
