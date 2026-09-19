package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mixin.ftbquests.ItemTaskSequenceAccessor;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Performs explicit, one-shot FTB item-task detection against an RS or BD snapshot. */
public final class StorageQuestScanService {

    private static final long REQUEST_COOLDOWN_TICKS = 20L;
    private static final int TASKS_PER_TEAM_PER_TICK = 8;
    private static final int TASKS_PER_SERVER_PER_TICK = 32;
    private static final Map<UUID, Long> LAST_TEAM_REQUEST = new HashMap<>();
    private static final Map<UUID, ScanJob> ACTIVE_JOBS = new HashMap<>();

    private StorageQuestScanService() {
    }

    public static void requestScan(ServerPlayer player) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        TeamData data = TeamData.get(player);
        if (file == null || file.isLoading() || data == null || data.isLocked()) return;

        long now = player.serverLevel().getGameTime();
        UUID teamId = data.getTeamId();
        if (ACTIVE_JOBS.containsKey(teamId)) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.storage_scan.busy"));
            return;
        }
        LAST_TEAM_REQUEST.entrySet().removeIf(entry -> now < entry.getValue()
                || now - entry.getValue() > 1_200L);
        Long previous = LAST_TEAM_REQUEST.get(teamId);
        if (previous != null && now >= previous && now - previous < REQUEST_COOLDOWN_TICKS) return;
        LAST_TEAM_REQUEST.put(teamId, now);

        Optional<CraftStorageEndpoint> endpoint = StorageRestockSupport.resolve(player);
        if (endpoint.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.storage_scan.no_network"));
            return;
        }

        StorageSnapshotResult snapshotResult = endpoint.orElseThrow().snapshot(player);
        Optional<StorageSnapshot> snapshot = snapshotResult.snapshot();
        if (!snapshotResult.successful() || snapshot.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.storage_scan.failed"));
            return;
        }

        List<Long> taskIds = new ArrayList<>();
        for (Task task : file.getSubmitTasks()) {
            if (task instanceof ItemTask itemTask && isStructurallyEligible(itemTask)) {
                taskIds.add(FtbQuestObjectId.getId(itemTask));
            }
        }
        if (taskIds.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.storage_scan.none"));
            return;
        }

        ACTIVE_JOBS.put(teamId, new ScanJob(player.getUUID(), List.copyOf(taskIds),
                snapshot.orElseThrow()));
        player.sendSystemMessage(Component.translatable("rsi.ftb_quest.storage_scan.started"));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE_JOBS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        int remainingServerBudget = TASKS_PER_SERVER_PER_TICK;

        Iterator<Map.Entry<UUID, ScanJob>> iterator = ACTIVE_JOBS.entrySet().iterator();
        while (iterator.hasNext() && remainingServerBudget > 0) {
            Map.Entry<UUID, ScanJob> entry = iterator.next();
            ScanJob job = entry.getValue();
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
                        "[RSI-FTBQuests] Failed to scan storage tasks for team {}",
                        entry.getKey(), exception);
                player.sendSystemMessage(Component.translatable("rsi.ftb_quest.storage_scan.failed"));
                iterator.remove();
                continue;
            }

            if (job.finished()) {
                iterator.remove();
                if (!job.cancelled) sendResult(player, job.updated);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE_JOBS.clear();
        LAST_TEAM_REQUEST.clear();
    }

    private static int processBatch(ServerPlayer player, UUID expectedTeamId,
                                    ScanJob job, int allowance) {
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
                Task task = file.getTask(job.nextTaskId());
                if (task instanceof ItemTask itemTask && isAvailable(data, itemTask)) {
                    long available = countMatching(job.snapshot, itemTask);
                    long current = data.getProgress(itemTask);
                    long target = Math.min(itemTask.getMaxProgress(), available);
                    if (target > current) {
                        data.setProgress(itemTask, target);
                        job.updated++;
                    }
                }
            }
        });
        return job.cursor - startCursor;
    }

    private static boolean isStructurallyEligible(ItemTask task) {
        return !task.consumesResources()
                && !task.isOnlyFromCrafting()
                && !task.isTaskScreenOnly();
    }

    private static boolean isAvailable(TeamData data, ItemTask task) {
        return isStructurallyEligible(task)
                && !data.isCompleted(task)
                && task.getQuest().isVisible(data)
                && data.canStartTasks(task.getQuest())
                && ((ItemTaskSequenceAccessor) (Object) task).rsi$checkTaskSequence(data);
    }

    private static long countMatching(StorageSnapshot snapshot, ItemTask task) {
        long total = 0L;
        for (StoredItem stored : snapshot.items()) {
            boolean matches;
            try {
                matches = task.test(stored.stack());
            } catch (RuntimeException | LinkageError exception) {
                continue;
            }
            if (!matches) continue;
            total = saturatedAdd(total, stored.amount());
            if (total >= task.getMaxProgress()) return task.getMaxProgress();
        }
        return total;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static void sendResult(ServerPlayer player, int updated) {
        if (updated == 0) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.storage_scan.none"));
        } else {
            player.sendSystemMessage(Component.translatable(
                    "rsi.ftb_quest.storage_scan.completed", updated));
        }
    }

    private static final class ScanJob {
        private final UUID playerId;
        private final List<Long> taskIds;
        private final StorageSnapshot snapshot;
        private int cursor;
        private int updated;
        private boolean cancelled;

        private ScanJob(UUID playerId, List<Long> taskIds, StorageSnapshot snapshot) {
            this.playerId = playerId;
            this.taskIds = taskIds;
            this.snapshot = snapshot;
        }

        private long nextTaskId() {
            return taskIds.get(cursor++);
        }

        private boolean finished() {
            return cursor >= taskIds.size();
        }

        private void cancel() {
            cancelled = true;
            cursor = taskIds.size();
        }
    }
}
