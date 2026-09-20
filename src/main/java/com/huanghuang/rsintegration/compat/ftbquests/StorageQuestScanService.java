package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Performs explicit, one-shot FTB item-task detection against storage or player items. */
public final class StorageQuestScanService {

    private static final long REQUEST_COOLDOWN_TICKS = 20L;
    private static final int TASKS_PER_TEAM_PER_TICK = 8;
    private static final int TASKS_PER_SERVER_PER_TICK = 32;
    private static final long AUTOMATIC_SCAN_DELAY_TICKS = 2L;
    private static final long AUTOMATIC_SCAN_RETRY_TICKS = 20L;
    private static final int AUTOMATIC_SCANS_PER_TICK = 2;
    private static final Map<UUID, Long> LAST_TEAM_REQUEST = new HashMap<>();
    private static final Map<UUID, ScanJob> ACTIVE_JOBS = new HashMap<>();
    private static final Map<UUID, Long> PENDING_AUTOMATIC_SCANS = new HashMap<>();

    private StorageQuestScanService() {
    }

    public static void requestScan(ServerPlayer player) {
        ScanRequest request = beginRequest(player);
        if (request == null) return;

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

        startScan(player, request, ScanKind.STORAGE, snapshotItems(snapshot.orElseThrow()));
    }

    /** Scans the complete player inventory and, when installed, every Curios slot. */
    public static void requestInventoryScan(ServerPlayer player) {
        ScanRequest request = beginRequest(player);
        if (request == null) return;

        List<ItemStack> curios = CuriosAccess.isPresent()
                ? CuriosAccess.stacks(player) : List.of();
        startScan(player, request, ScanKind.INVENTORY,
                QuestScanItems.fromPlayer(player, curios));
    }

    /** Queues a silent current-state scan after a completion may unlock more tasks. */
    public static void scheduleRetrospectiveScan(TeamData data) {
        if (!ExternalItemProgressBridge.isEnabled() || data == null) return;
        Collection<ServerPlayer> members = data.getOnlineMembers();
        if (members == null) return;
        for (ServerPlayer member : members) scheduleRetrospectiveScan(member);
    }

    /** Also catches tasks which were already unlocked before the player joined. */
    public static void scheduleRetrospectiveScan(ServerPlayer player) {
        if (!ExternalItemProgressBridge.isEnabled() || player == null) return;
        long dueTick = (long) player.server.getTickCount() + AUTOMATIC_SCAN_DELAY_TICKS;
        PENDING_AUTOMATIC_SCANS.merge(player.getUUID(), dueTick, Math::min);
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
    }

    private static void dispatchAutomaticScans(MinecraftServer server) {
        if (!ExternalItemProgressBridge.isEnabled()) {
            PENDING_AUTOMATIC_SCANS.clear();
            return;
        }
        long now = server.getTickCount();
        int started = 0;
        Iterator<Map.Entry<UUID, Long>> iterator = PENDING_AUTOMATIC_SCANS.entrySet().iterator();
        while (iterator.hasNext() && started < AUTOMATIC_SCANS_PER_TICK) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() > now) continue;

            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            ServerQuestFile file = ServerQuestFile.INSTANCE;
            TeamData data = TeamData.get(player);
            if (file == null || file.isLoading() || data == null || data.isLocked()) {
                entry.setValue(now + AUTOMATIC_SCAN_RETRY_TICKS);
                continue;
            }
            if (ACTIVE_JOBS.containsKey(data.getTeamId())) {
                entry.setValue(now + AUTOMATIC_SCAN_RETRY_TICKS);
                continue;
            }

            iterator.remove();
            startAutomaticScan(player, new ScanRequest(file, data.getTeamId()));
            started++;
        }
    }

    private static void startAutomaticScan(ServerPlayer player, ScanRequest request) {
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

        startScan(player, request, ScanKind.AUTOMATIC, items);
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
        INVENTORY("inventory", "rsi.ftb_quest.inventory_scan.started",
                "rsi.ftb_quest.inventory_scan.failed", "rsi.ftb_quest.inventory_scan.completed",
                "rsi.ftb_quest.inventory_scan.none"),
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
}
