package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mixin.ftbquests.ItemTaskSequenceAccessor;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.util.CuriosAccess;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Performs explicit, one-shot FTB item-task detection against storage or player items. */
public final class StorageQuestScanService {

    private static final long REQUEST_COOLDOWN_TICKS = 20L;
    private static final int TASKS_PER_TEAM_PER_TICK = 8;
    private static final int TASKS_PER_SERVER_PER_TICK = 32;
    private static final long AUTOMATIC_SCAN_DELAY_TICKS = 2L;
    private static final long AUTOMATIC_SCAN_RETRY_TICKS = 20L;
    private static final long AUTOMATIC_SCAN_TIMEOUT_TICKS = 200L;
    private static final int AUTOMATIC_SCANS_PER_TICK = 2;
    private static final long PLAYER_ITEM_SCAN_DELAY_TICKS = 2L;
    private static final long PLAYER_ITEM_SCAN_TIMEOUT_TICKS = 100L;
    private static final int PLAYER_ITEM_SCANS_PER_TICK = 8;
    private static final Map<UUID, Long> LAST_TEAM_REQUEST = new HashMap<>();
    private static final Map<UUID, ScanJob> ACTIVE_JOBS = new HashMap<>();
    // Quest completion callbacks can enqueue a follow-up while the server tick
    // dispatcher is still draining this table. A plain HashMap can fail inside
    // compute() when those callbacks interleave with dispatch.
    private static final Map<UUID, PendingAutomaticScan> PENDING_AUTOMATIC_SCANS =
            new ConcurrentHashMap<>();
    private static final Map<UUID, PendingPlayerItemScan> PENDING_PLAYER_ITEM_SCANS =
            new ConcurrentHashMap<>();

    private StorageQuestScanService() {
    }

    public static void requestScan(ServerPlayer player) {
        if (!RSIntegrationConfig.ENABLE_FTB_QUEST_STORAGE_SCAN_BUTTON.get()) return;
        ScanRequest request = beginRequest(player);
        if (request == null) return;

        List<QuestScanItems.Entry> items = new ArrayList<>();
        List<ItemStack> curios = CuriosAccess.isPresent()
                ? CuriosAccess.stacks(player) : List.of();
        items.addAll(QuestScanItems.fromPlayer(player, curios));

        try {
            Optional<CraftStorageEndpoint> endpoint = StorageRestockSupport.resolve(player);
            if (endpoint.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.ftb_quest.storage_scan.no_network"));
            } else {
                StorageSnapshotResult result = endpoint.orElseThrow().snapshot(player);
                Optional<StorageSnapshot> snapshot = result.snapshot();
                if (result.successful() && snapshot.isPresent()) {
                    items.addAll(snapshotItems(snapshot.orElseThrow()));
                } else {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.ftb_quest.storage_scan.failed"));
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-FTBQuests] Full item-task storage snapshot unavailable for {}",
                    player.getGameProfile().getName(), exception);
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.storage_scan.failed"));
        }

        startScan(player, request, ScanKind.STORAGE, items);
    }

    /** Captures the item tasks which can accept progress at this exact point in time. */
    public static Set<Long> snapshotAvailableTaskIds(TeamData data) {
        if (!ExternalItemProgressBridge.isEnabled() || data == null) return Set.of();
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || file.isLoading() || data.isLocked()) return Set.of();

        Set<Long> taskIds = new LinkedHashSet<>();
        for (Task task : file.getSubmitTasks()) {
            if (task instanceof ItemTask itemTask && isAvailable(data, itemTask)) {
                taskIds.add(FtbQuestObjectId.getId(itemTask));
            }
        }
        return taskIds;
    }

    /** Queues only item tasks made available by the completion which just occurred. */
    public static void scheduleNewlyAvailableScan(TeamData data, Collection<Long> availableBefore) {
        if (!ExternalItemProgressBridge.isEnabled() || data == null) return;
        List<Long> taskIds = QuestTaskAvailability.newlyAvailableTaskIds(
                availableBefore, snapshotAvailableTaskIds(data));
        if (taskIds.isEmpty()) return;

        Collection<ServerPlayer> members = data.getOnlineMembers();
        if (members == null) return;
        for (ServerPlayer member : members) {
            if (member == null) continue;
            long now = member.server.getTickCount();
            long dueTick = now + AUTOMATIC_SCAN_DELAY_TICKS;
            long deadlineTick = now + AUTOMATIC_SCAN_TIMEOUT_TICKS;
            PENDING_AUTOMATIC_SCANS.compute(member.getUUID(), (playerId, pending) -> {
                if (pending == null) {
                    return new PendingAutomaticScan(dueTick, deadlineTick, taskIds);
                }
                synchronized (pending) {
                    pending.taskIds.addAll(taskIds);
                    pending.dueTick = Math.min(pending.dueTick, dueTick);
                    pending.deadlineTick = Math.max(pending.deadlineTick, deadlineTick);
                }
                return pending;
            });
        }
    }

    /**
     * 在奖励物品或 Curios 槽位变更后，重新检查玩家当前实际持有的物品。
     *
     * <p>FTB Quests 原生监听只观察玩家背包，Curios 的强制替换不会触发它；
     * 延迟几个 tick 可以等命令奖励和 Curios 替换都完成后再取快照。</p>
     */
    public static void schedulePlayerItemScan(ServerPlayer player) {
        if (!ExternalItemProgressBridge.isEnabled() || player == null) return;
        long now = player.server.getTickCount();
        PENDING_PLAYER_ITEM_SCANS.compute(player.getUUID(), (playerId, pending) -> {
            if (pending == null) {
                return new PendingPlayerItemScan(now + PLAYER_ITEM_SCAN_DELAY_TICKS,
                        now + PLAYER_ITEM_SCAN_TIMEOUT_TICKS);
            }
            pending.dueTick = Math.min(pending.dueTick, now + PLAYER_ITEM_SCAN_DELAY_TICKS);
            pending.deadlineTick = Math.max(pending.deadlineTick,
                    now + PLAYER_ITEM_SCAN_TIMEOUT_TICKS);
            return pending;
        });
    }

    private static ScanRequest beginRequest(ServerPlayer player) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        TeamData data = TeamData.get(player);
        if (file == null || file.isLoading() || data == null || data.isLocked()) return null;

        long now = player.serverLevel().getGameTime();
        UUID teamId = data.getTeamId();
        if (ACTIVE_JOBS.containsKey(teamId)) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.storage_scan.busy"));
            return null;
        }
        LAST_TEAM_REQUEST.entrySet().removeIf(entry -> now < entry.getValue()
                || now - entry.getValue() > 1_200L);
        Long previous = LAST_TEAM_REQUEST.get(teamId);
        if (previous != null && now >= previous && now - previous < REQUEST_COOLDOWN_TICKS) return null;
        LAST_TEAM_REQUEST.put(teamId, now);
        return new ScanRequest(file, teamId);
    }

    private static void startScan(ServerPlayer player, ScanRequest request,
                                  ScanKind kind, List<QuestScanItems.Entry> items) {
        List<Long> taskIds = new ArrayList<>();
        for (Task task : request.file().getSubmitTasks()) {
            if (task instanceof ItemTask itemTask && isStructurallyEligible(itemTask)) {
                taskIds.add(FtbQuestObjectId.getId(itemTask));
            }
        }
        startScan(player, request, kind, items, taskIds);
    }

    private static void startScan(ServerPlayer player, ScanRequest request,
                                  ScanKind kind, List<QuestScanItems.Entry> items,
                                  Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            if (kind.noneKey != null) {
                player.sendSystemMessage(Component.translatable(kind.noneKey));
            }
            return;
        }

        ACTIVE_JOBS.put(request.teamId(), new ScanJob(player.getUUID(), List.copyOf(taskIds),
                items, kind));
        if (kind.startedKey != null) {
            player.sendSystemMessage(Component.translatable(kind.startedKey));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        dispatchPlayerItemScans(server);
        dispatchAutomaticScans(server);
        if (ACTIVE_JOBS.isEmpty()) return;
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
                        "[RSI-FTBQuests] Failed to scan {} tasks for team {}",
                        job.kind.logName,
                        entry.getKey(), exception);
                if (job.kind.failedKey != null) {
                    player.sendSystemMessage(Component.translatable(job.kind.failedKey));
                }
                iterator.remove();
                continue;
            }

            if (job.finished()) {
                iterator.remove();
                if (!job.cancelled) sendResult(player, job.kind, job.updated);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE_JOBS.clear();
        LAST_TEAM_REQUEST.clear();
        PENDING_AUTOMATIC_SCANS.clear();
        PENDING_PLAYER_ITEM_SCANS.clear();
    }

    private static void dispatchPlayerItemScans(MinecraftServer server) {
        if (!ExternalItemProgressBridge.isEnabled()) {
            PENDING_PLAYER_ITEM_SCANS.clear();
            return;
        }
        long now = server.getTickCount();
        int started = 0;
        Iterator<Map.Entry<UUID, PendingPlayerItemScan>> iterator =
                PENDING_PLAYER_ITEM_SCANS.entrySet().iterator();
        while (iterator.hasNext() && started < PLAYER_ITEM_SCANS_PER_TICK) {
            Map.Entry<UUID, PendingPlayerItemScan> entry = iterator.next();
            PendingPlayerItemScan pending = entry.getValue();
            if (now > pending.deadlineTick) {
                iterator.remove();
                continue;
            }
            if (pending.dueTick > now) continue;

            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            ServerQuestFile file = ServerQuestFile.INSTANCE;
            TeamData data = TeamData.get(player);
            if (file == null || file.isLoading() || data == null || data.isLocked()) {
                pending.retryAt(now);
                continue;
            }

            iterator.remove();
            scanPlayerItems(player, file, data);
            started++;
        }
    }

    private static void scanPlayerItems(ServerPlayer player, ServerQuestFile file, TeamData data) {
        List<ItemStack> curios = CuriosAccess.isPresent()
                ? CuriosAccess.stacks(player) : List.of();
        List<QuestScanItems.Entry> items = QuestScanItems.fromPlayer(player, curios);
        file.withPlayerContext(player, () -> {
            for (Task task : file.getSubmitTasks()) {
                if (!(task instanceof ItemTask itemTask)
                        || !isAvailableAfterRewardClaim(data, itemTask)) continue;
                long available = countMatching(items, itemTask);
                long current = data.getProgress(itemTask);
                long target = Math.min(itemTask.getMaxProgress(), available);
                if (target > current) data.setProgress(itemTask, target);
            }
        });
    }

    private static void dispatchAutomaticScans(MinecraftServer server) {
        if (!ExternalItemProgressBridge.isEnabled()) {
            PENDING_AUTOMATIC_SCANS.clear();
            return;
        }
        long now = server.getTickCount();
        int started = 0;
        Iterator<Map.Entry<UUID, PendingAutomaticScan>> iterator =
                PENDING_AUTOMATIC_SCANS.entrySet().iterator();
        while (iterator.hasNext() && started < AUTOMATIC_SCANS_PER_TICK) {
            Map.Entry<UUID, PendingAutomaticScan> entry = iterator.next();
            PendingAutomaticScan pending = entry.getValue();
            if (now > pending.deadlineTick) {
                iterator.remove();
                continue;
            }
            if (pending.dueTick > now) continue;

            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            ServerQuestFile file = ServerQuestFile.INSTANCE;
            TeamData data = TeamData.get(player);
            if (file == null || file.isLoading() || data == null || data.isLocked()) {
                pending.retryAt(now);
                continue;
            }
            if (ACTIVE_JOBS.containsKey(data.getTeamId())) {
                pending.retryAt(now);
                continue;
            }

            iterator.remove();
            startAutomaticScan(player, new ScanRequest(file, data.getTeamId()), pending.taskIds);
            started++;
        }
    }

    private static void startAutomaticScan(ServerPlayer player, ScanRequest request,
                                           Collection<Long> taskIds) {
        List<QuestScanItems.Entry> items = new ArrayList<>();
        List<ItemStack> curios = CuriosAccess.isPresent()
                ? CuriosAccess.stacks(player) : List.of();
        items.addAll(QuestScanItems.fromPlayer(player, curios));

        try {
            Optional<CraftStorageEndpoint> endpoint = StorageRestockSupport.resolve(player);
            if (endpoint.isPresent()) {
                StorageSnapshotResult result = endpoint.orElseThrow().snapshot(player);
                if (result.successful()) {
                    result.snapshot().ifPresent(snapshot -> items.addAll(snapshotItems(snapshot)));
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-FTBQuests] Automatic retrospective storage snapshot unavailable for {}",
                    player.getGameProfile().getName(), exception);
        }

        startScan(player, request, ScanKind.AUTOMATIC, items, taskIds);
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
                    long available = countMatching(job.items, itemTask);
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

    /** 奖励领取后的实物检测不要求任务线当前可见，但仍遵守任务前置条件。 */
    private static boolean isAvailableAfterRewardClaim(TeamData data, ItemTask task) {
        return isStructurallyEligible(task)
                && !data.isCompleted(task)
                && data.canStartTasks(task.getQuest())
                && ((ItemTaskSequenceAccessor) (Object) task).rsi$checkTaskSequence(data);
    }

    private static long countMatching(List<QuestScanItems.Entry> items, ItemTask task) {
        long total = 0L;
        for (QuestScanItems.Entry item : items) {
            boolean matches;
            try {
                matches = task.test(item.stack());
            } catch (RuntimeException | LinkageError exception) {
                continue;
            }
            if (!matches) continue;
            total = saturatedAdd(total, item.amount());
            if (total >= task.getMaxProgress()) return task.getMaxProgress();
        }
        return total;
    }

    private static List<QuestScanItems.Entry> snapshotItems(StorageSnapshot snapshot) {
        return QuestScanItems.fromStorage(snapshot);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static void sendResult(ServerPlayer player, ScanKind kind, int updated) {
        if (kind.completedKey == null || kind.noneKey == null) return;
        if (updated == 0) {
            player.sendSystemMessage(Component.translatable(kind.noneKey));
        } else {
            player.sendSystemMessage(Component.translatable(kind.completedKey, updated));
        }
    }

    private record ScanRequest(ServerQuestFile file, UUID teamId) {
    }

    private enum ScanKind {
        STORAGE("storage", "rsi.ftb_quest.storage_scan.started",
                "rsi.ftb_quest.storage_scan.failed", "rsi.ftb_quest.storage_scan.completed",
                "rsi.ftb_quest.storage_scan.none"),
        AUTOMATIC("automatic", null, null, null, null);

        private final String logName;
        private final String startedKey;
        private final String failedKey;
        private final String completedKey;
        private final String noneKey;

        ScanKind(String logName, String startedKey, String failedKey,
                 String completedKey, String noneKey) {
            this.logName = logName;
            this.startedKey = startedKey;
            this.failedKey = failedKey;
            this.completedKey = completedKey;
            this.noneKey = noneKey;
        }
    }

    private static final class ScanJob {
        private final UUID playerId;
        private final List<Long> taskIds;
        private final List<QuestScanItems.Entry> items;
        private final ScanKind kind;
        private int cursor;
        private int updated;
        private boolean cancelled;

        private ScanJob(UUID playerId, List<Long> taskIds, List<QuestScanItems.Entry> items,
                        ScanKind kind) {
            this.playerId = playerId;
            this.taskIds = taskIds;
            this.items = List.copyOf(items);
            this.kind = kind;
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

    private static final class PendingAutomaticScan {
        private long dueTick;
        private long deadlineTick;
        private final Set<Long> taskIds;

        private PendingAutomaticScan(long dueTick, long deadlineTick, Collection<Long> taskIds) {
            this.dueTick = dueTick;
            this.deadlineTick = deadlineTick;
            this.taskIds = new LinkedHashSet<>(taskIds);
        }

        private void retryAt(long now) {
            dueTick = Math.min(deadlineTick, now + AUTOMATIC_SCAN_RETRY_TICKS);
        }
    }

    private static final class PendingPlayerItemScan {
        private long dueTick;
        private long deadlineTick;

        private PendingPlayerItemScan(long dueTick, long deadlineTick) {
            this.dueTick = dueTick;
            this.deadlineTick = deadlineTick;
        }

        private void retryAt(long now) {
            dueTick = Math.min(deadlineTick, now + AUTOMATIC_SCAN_RETRY_TICKS);
        }
    }
}
