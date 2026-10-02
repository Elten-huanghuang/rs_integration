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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 分批执行仓储和玩家物品的 FTB 任务检测。 */
public final class StorageQuestScanService {

    private static final long REQUEST_COOLDOWN_TICKS = 20L;
    private static final int TASKS_PER_JOB_PER_TICK = 8;
    private static final int TASKS_PER_SERVER_PER_TICK = 32;
    /** 任务数量之外再限制匹配耗时；单个任务结束后检查预算。 */
    private static final long MATCHING_BUDGET_NANOS = 2_000_000L;
    private static final long AUTOMATIC_SCAN_DELAY_TICKS = 2L;
    private static final long AUTOMATIC_SCAN_RETRY_TICKS = 20L;
    private static final long AUTOMATIC_SCAN_TIMEOUT_TICKS = 200L;
    /** 连续解锁任务时，避免每个任务都重新读取一次完整存储网络。 */
    private static final long AUTOMATIC_SCAN_MIN_INTERVAL_TICKS = 20L;
    private static final int AUTOMATIC_SCANS_PER_TICK = 2;
    private static final long PLAYER_ITEM_SCAN_DELAY_TICKS = 2L;
    private static final long PLAYER_ITEM_SCAN_TIMEOUT_TICKS = 100L;
    private static final long PLAYER_ITEM_SCAN_MIN_INTERVAL_TICKS = 20L;
    private static final int PLAYER_ITEM_SCANS_PER_TICK = 2;
    private static final Map<UUID, Long> LAST_TEAM_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_AUTOMATIC_SCAN = new HashMap<>();
    private static final Map<UUID, Long> LAST_PLAYER_ITEM_SCAN = new HashMap<>();
    private static final Map<UUID, ScanJob> ACTIVE_JOBS = new HashMap<>();
    // 玩家重扫单独排队，不能覆盖同队伍正在进行的仓储扫描。
    private static final Map<UUID, ScanJob> ACTIVE_PLAYER_ITEM_SCANS = new HashMap<>();
    // 所有扫描轮流使用同一份预算，防止复杂过滤器让排在后面的玩家长期得不到处理。
    private static final Deque<ScanJob> SCAN_QUEUE = new ArrayDeque<>();
    // 完成任务的回调可能在派发扫描时再次入队，避免 HashMap.compute 的重入问题。
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

        enqueueJob(new ScanJob(player.getUUID(), request.teamId(), List.copyOf(taskIds), items, kind));
        if (kind.startedKey != null) {
            player.sendSystemMessage(Component.translatable(kind.startedKey));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        LAST_AUTOMATIC_SCAN.entrySet().removeIf(entry -> now < entry.getValue()
                || now - entry.getValue() > 1_200L);
        LAST_PLAYER_ITEM_SCAN.entrySet().removeIf(entry -> now < entry.getValue()
                || now - entry.getValue() > 1_200L);
        if (!ExternalItemProgressBridge.isEnabled()) {
            PENDING_AUTOMATIC_SCANS.clear();
            PENDING_PLAYER_ITEM_SCANS.clear();
            LAST_AUTOMATIC_SCAN.clear();
            LAST_PLAYER_ITEM_SCAN.clear();
            ACTIVE_PLAYER_ITEM_SCANS.clear();
            ACTIVE_JOBS.values().removeIf(job -> job.kind == ScanKind.AUTOMATIC);
            SCAN_QUEUE.removeIf(job -> job.kind != ScanKind.STORAGE);
        }
        if (ACTIVE_JOBS.isEmpty() && PENDING_AUTOMATIC_SCANS.isEmpty()
                && PENDING_PLAYER_ITEM_SCANS.isEmpty() && ACTIVE_PLAYER_ITEM_SCANS.isEmpty()) return;
        dispatchPlayerItemScans(server);
        dispatchAutomaticScans(server);
        processJobs(server);
    }

    private static void enqueueJob(ScanJob job) {
        activeJobs(job).put(jobKey(job), job);
        SCAN_QUEUE.addLast(job);
    }

    private static Map<UUID, ScanJob> activeJobs(ScanJob job) {
        return job.kind == ScanKind.PLAYER_ITEMS ? ACTIVE_PLAYER_ITEM_SCANS : ACTIVE_JOBS;
    }

    private static UUID jobKey(ScanJob job) {
        return job.kind == ScanKind.PLAYER_ITEMS ? job.playerId : job.teamId;
    }

    private static void processJobs(MinecraftServer server) {
        long matchingStartedAt = System.nanoTime();
        int remainingServerBudget = TASKS_PER_SERVER_PER_TICK;
        int jobsRemaining = SCAN_QUEUE.size();
        while (jobsRemaining-- > 0 && remainingServerBudget > 0 && hasMatchingTime(matchingStartedAt)) {
            ScanJob job = SCAN_QUEUE.removeFirst();
            Map<UUID, ScanJob> jobs = activeJobs(job);
            UUID key = jobKey(job);
            if (jobs.get(key) != job) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(job.playerId);
            if (player == null) {
                jobs.remove(key);
                continue;
            }

            int allowance = Math.min(TASKS_PER_JOB_PER_TICK, remainingServerBudget);
            try {
                int attempted = processBatch(player, job, allowance, matchingStartedAt);
                remainingServerBudget -= attempted;
            } catch (RuntimeException | LinkageError exception) {
                // 失败的批次也占用预算，避免异常任务绕过每 tick 的数量上限。
                remainingServerBudget -= allowance;
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Failed to scan {} tasks for team {}",
                        job.kind.logName,
                        job.teamId, exception);
                if (job.kind.failedKey != null) {
                    player.sendSystemMessage(Component.translatable(job.kind.failedKey));
                }
                jobs.remove(key);
                continue;
            }

            if (job.finished()) {
                jobs.remove(key);
                if (!job.cancelled) sendResult(player, job.kind, job.updated);
            } else {
                SCAN_QUEUE.addLast(job);
            }
        }
    }

    private static boolean hasMatchingTime(long matchingStartedAt) {
        return System.nanoTime() - matchingStartedAt < MATCHING_BUDGET_NANOS;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE_JOBS.clear();
        LAST_TEAM_REQUEST.clear();
        LAST_AUTOMATIC_SCAN.clear();
        LAST_PLAYER_ITEM_SCAN.clear();
        ACTIVE_PLAYER_ITEM_SCANS.clear();
        SCAN_QUEUE.clear();
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
            // 扫描较多任务时保留期间产生的新变更，不能因等待当前扫描而丢失后续请求。
            if (ACTIVE_PLAYER_ITEM_SCANS.containsKey(entry.getKey())) {
                pending.deadlineTick = Math.max(pending.deadlineTick, now + PLAYER_ITEM_SCAN_TIMEOUT_TICKS);
                continue;
            }
            if (now > pending.deadlineTick) {
                iterator.remove();
                continue;
            }
            if (pending.dueTick > now) continue;
            Long lastScan = LAST_PLAYER_ITEM_SCAN.get(entry.getKey());
            if (lastScan != null && now - lastScan < PLAYER_ITEM_SCAN_MIN_INTERVAL_TICKS) continue;

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
            LAST_PLAYER_ITEM_SCAN.put(entry.getKey(), now);
            startPlayerItemScan(player, file, data);
            started++;
        }
    }

    private static void startPlayerItemScan(ServerPlayer player, ServerQuestFile file, TeamData data) {
        List<ItemStack> curios = CuriosAccess.isPresent()
                ? CuriosAccess.stacks(player) : List.of();
        List<QuestScanItems.Entry> items = QuestScanItems.fromPlayer(player, curios);
        List<Long> taskIds = new ArrayList<>();
        for (Task task : file.getSubmitTasks()) {
            if (task instanceof ItemTask itemTask && isStructurallyEligible(itemTask)) {
                taskIds.add(FtbQuestObjectId.getId(itemTask));
            }
        }
        if (!taskIds.isEmpty()) {
            enqueueJob(new ScanJob(player.getUUID(), data.getTeamId(), taskIds, items, ScanKind.PLAYER_ITEMS));
        }
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

            Long lastScan = LAST_AUTOMATIC_SCAN.get(entry.getKey());
            if (lastScan != null) {
                long nextAllowed = lastScan + AUTOMATIC_SCAN_MIN_INTERVAL_TICKS;
                if (now < nextAllowed) {
                    pending.deferUntil(nextAllowed);
                    continue;
                }
            }

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
            LAST_AUTOMATIC_SCAN.put(entry.getKey(), now);
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

    private static int processBatch(ServerPlayer player, ScanJob job, int allowance,
                                    long matchingStartedAt) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        TeamData data = TeamData.get(player);
        if (file == null || file.isLoading() || data == null || data.isLocked()
                || !job.teamId.equals(data.getTeamId())) {
            job.cancel();
            return 0;
        }

        int[] attempted = {0};
        file.withPlayerContext(player, () -> {
            attempted[0] = job.tasks.processBatch(allowance,
                    () -> hasMatchingTime(matchingStartedAt), taskId -> {
                        Task task = file.getTask(taskId);
                        if (task instanceof ItemTask itemTask && (job.kind == ScanKind.PLAYER_ITEMS
                                ? isAvailableAfterRewardClaim(data, itemTask) : isAvailable(data, itemTask))) {
                            long available = countMatching(job.items, itemTask);
                            long current = data.getProgress(itemTask);
                            long target = Math.min(itemTask.getMaxProgress(), available);
                            if (target > current) {
                                data.setProgress(itemTask, target);
                                job.updated++;
                            }
                        }
                    });
        });
        return attempted[0];
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
        return QuestScanItems.countMatching(items, task, task.getMaxProgress());
    }

    private static List<QuestScanItems.Entry> snapshotItems(StorageSnapshot snapshot) {
        return QuestScanItems.fromStorage(snapshot);
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
        AUTOMATIC("automatic", null, null, null, null),
        PLAYER_ITEMS("player items", null, null, null, null);

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
        private final UUID teamId;
        private final QuestTaskScan tasks;
        private final List<QuestScanItems.Entry> items;
        private final ScanKind kind;
        private int updated;
        private boolean cancelled;

        private ScanJob(UUID playerId, UUID teamId, List<Long> taskIds,
                        List<QuestScanItems.Entry> items, ScanKind kind) {
            this.playerId = playerId;
            this.teamId = teamId;
            this.tasks = new QuestTaskScan(taskIds);
            this.items = List.copyOf(items);
            this.kind = kind;
        }

        private boolean finished() {
            return tasks.finished();
        }

        private void cancel() {
            cancelled = true;
            tasks.cancel();
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

        private void deferUntil(long tick) {
            dueTick = Math.min(deadlineTick, Math.max(dueTick, tick));
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
