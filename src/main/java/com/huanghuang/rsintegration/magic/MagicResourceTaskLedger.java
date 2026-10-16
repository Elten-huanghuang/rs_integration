package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 跨任务保存的魔法资源预留摘要与机器注入去向。 */
public final class MagicResourceTaskLedger extends SavedData {
    private static final String DATA_NAME = "rs_integration_magic_resource_tasks";
    private static final int MAX_INJECTION_RECORDS = 4096;
    private static final int MAX_PENDING_RECORDS = 512;
    /** 待核对记录的保留时长：超期后自动淘汰，避免永久堆积。 */
    private static final long PENDING_RETENTION_TICKS = 20L * 60L * 60L * 24L * 7L;

    public enum InjectionState {
        ACTIVE,
        DISK_PENDING,
        UNKNOWN
    }

    /** 待核对记录的产生原因，用于后台核对按优先级处理。 */
    public enum PendingReason {
        /** 机器位置无法确认归属（区块未加载、方块被拆除等）。 */
        NO_TARGET,
        /** 机器接口只能回收一部分，剩余量无法确认。 */
        MACHINE_PARTIAL,
        /** BD 预留因网络未授权或网络已重建而失去归属。 */
        BD_NETWORK,
        /** 任务结束、取消或玩家离线，剩余量仍留在 BD 网络中。 */
        BD_TASK_CLOSED
    }

    /**
     * 需要人工核对的一条记录。这些记录不进入自动回收流程：没有可安全执行的反向
     * 操作时自动退款只会伪造守恒，因此只保留核对依据，并附上失效时的 BD 网络 ID
     * 以便重连后继续核对。
     */
    public record Pending(UUID id, @Nullable UUID taskId, MagicResourceKind kind, int amount,
                          int bdNetworkId, PendingReason reason, long recordedAt) {
    }

    private record Injection(UUID id, UUID taskId, MagicResourceKind kind,
                             ResourceLocation dimension, BlockPos position,
                             int amount, InjectionState state) {
        private Injection withAmount(int next) {
            return new Injection(id, taskId, kind, dimension, position, next, state);
        }

        private Injection unknown() {
            return new Injection(id, taskId, kind, dimension, position, amount, InjectionState.UNKNOWN);
        }

        private Injection withState(InjectionState next) {
            return new Injection(id, taskId, kind, dimension, position, amount, next);
        }
    }

    private final Map<UUID, Map<MagicResourceKind, Integer>> tasks = new LinkedHashMap<>();
    private final Map<UUID, Injection> injections = new LinkedHashMap<>();
    private final Set<UUID> pendingRecovery = new LinkedHashSet<>();
    private final Map<UUID, Pending> pendingAudits = new LinkedHashMap<>();
    private boolean loadedFromDisk;

    MagicResourceTaskLedger() {}

    MagicResourceTaskLedger(CompoundTag tag) {
        loadedFromDisk = true;
        for (Tag value : tag.getList("tasks", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            if (!entry.hasUUID("id")) continue;
            Map<MagicResourceKind, Integer> amounts = new LinkedHashMap<>();
            CompoundTag resources = entry.getCompound("resources");
            for (MagicResourceKind kind : MagicResourceKind.values()) {
                int amount = resources.getInt(kind.id());
                if (amount > 0) amounts.put(kind, amount);
            }
            if (!amounts.isEmpty()) tasks.put(entry.getUUID("id"), amounts);
        }
        for (Tag value : tag.getList("injections", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            Injection injection = readInjection(entry);
            if (injection != null) {
                injections.put(injection.id(), injection);
                if (injection.state() != InjectionState.UNKNOWN) {
                    pendingRecovery.add(injection.id());
                }
            }
        }
        for (Tag value : tag.getList("pendingAudits", Tag.TAG_COMPOUND)) {
            Pending entry = readPending((CompoundTag) value);
            // 归档记录不进入自动回收，这里只恢复核对依据。
            if (entry != null) pendingAudits.put(entry.id(), entry);
        }
    }

    public static MagicResourceTaskLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                MagicResourceTaskLedger::new, MagicResourceTaskLedger::new, DATA_NAME);
    }

    public void recordTask(UUID taskId, List<MagicResourceCost> costs) {
        Map<MagicResourceKind, Integer> amounts = new LinkedHashMap<>();
        for (MagicResourceCost cost : costs) {
            amounts.merge(cost.kind(), cost.amountPerOperation(),
                    (left, right) -> (int) Math.min(Integer.MAX_VALUE,
                            (long) left + right));
        }
        if (amounts.isEmpty()) {
            tasks.remove(taskId);
        } else {
            tasks.put(taskId, amounts);
        }
        setDirty();
    }

    public UUID recordInjection(UUID taskId, MagicResourceKind kind,
                                ResourceLocation dimension, BlockPos position, int amount) {
        pruneOldestUnknownIfNeeded();
        prunePendingAudits();
        UUID id = UUID.randomUUID();
        injections.put(id, new Injection(id, taskId, kind, dimension, position.immutable(),
                Math.max(0, amount), InjectionState.ACTIVE));
        pendingRecovery.add(id);
        setDirty();
        return id;
    }

    private void pruneOldestUnknownIfNeeded() {
        if (injections.size() < MAX_INJECTION_RECORDS) return;
        for (UUID id : List.copyOf(injections.keySet())) {
            Injection existing = injections.get(id);
            if (existing != null && existing.state() == InjectionState.UNKNOWN) {
                injections.remove(id);
                pendingRecovery.remove(id);
                setDirty();
                return;
            }
        }
    }

    /**
     * 把一条无法自动回收的注入记录归档为待核对记录。归档后不再进入 {@code tick}
     * 的自动回收，只提供核对依据；{@code bdNetworkId} 记录失效时所在的 BD 网络，
     * 便于服务器重连后按同一网络继续核对。
     */
    public void archive(@Nullable UUID taskId, MagicResourceKind kind, int amount,
                        int bdNetworkId, PendingReason reason) {
        archive(taskId, kind, amount, bdNetworkId, reason, 0L);
    }

    /** 同上，并记录产生时间（主世界游戏刻），用于超期淘汰。 */
    public void archive(@Nullable UUID taskId, MagicResourceKind kind, int amount,
                        int bdNetworkId, PendingReason reason, long recordedAt) {
        if (kind == null || amount <= 0) return;
        UUID id = UUID.randomUUID();
        pendingAudits.put(id, new Pending(id, taskId, kind, amount, bdNetworkId,
                reason == null ? PendingReason.NO_TARGET : reason, Math.max(0L, recordedAt)));
        prunePendingAudits();
        setDirty();
    }

    /** 交给后台核对界面使用的快照；按记录时间从早到晚排列。 */
    public List<Pending> pendingAudits() {
        prunePendingAudits();
        return List.copyOf(pendingAudits.values());
    }

    public int pendingAuditCount() {
        prunePendingAudits();
        return pendingAudits.size();
    }

    /** 核对完成后移除记录；返回是否确实存在。 */
    public boolean resolvePending(UUID pendingId) {
        if (pendingId == null || pendingAudits.remove(pendingId) == null) return false;
        setDirty();
        return true;
    }

    /** 待核对记录总量上限；超限时丢弃最早的一条，避免存档无限增长。 */
    private void prunePendingAudits() {
        while (pendingAudits.size() > MAX_PENDING_RECORDS) {
            UUID oldest = pendingAudits.keySet().iterator().next();
            pendingAudits.remove(oldest);
            setDirty();
        }
    }

    public void consumed(@Nullable UUID injectionId) {
        if (injectionId != null) {
            pendingRecovery.remove(injectionId);
            if (injections.remove(injectionId) != null) setDirty();
        }
    }

    public void recovered(UUID injectionId, int remaining) {
        Injection injection = injections.get(injectionId);
        if (injection == null) return;
        pendingRecovery.remove(injectionId);
        if (remaining <= 0) injections.remove(injectionId);
        else injections.put(injectionId, injection.withAmount(remaining).unknown());
        setDirty();
    }

    public void taskClosed(UUID taskId) {
        if (tasks.remove(taskId) != null) setDirty();
    }

    /** 服务器启动后逐 tick 尝试回收尚未确认去向的资源。 */
    public void tick(MinecraftServer server) {
        if (loadedFromDisk) {
            loadedFromDisk = false;
            if (!tasks.isEmpty()) {
                tasks.clear();
                setDirty();
            }
        }
        prunePendingAuditsByAge(server.overworld().getGameTime());
        if (pendingRecovery.isEmpty()) return;
        for (UUID id : List.copyOf(pendingRecovery)) {
            Injection injection = injections.get(id);
            if (injection == null || injection.state() == InjectionState.UNKNOWN) {
                pendingRecovery.remove(id);
                continue;
            }
            if (injection.state() == InjectionState.DISK_PENDING) {
                int accepted = MagicResourceEndpoint.insert(
                        server, injection.kind(), injection.amount(), true);
                if (accepted <= 0) continue;
                int remaining = injection.amount() - accepted;
                if (remaining <= 0) {
                    injections.remove(injection.id());
                    pendingRecovery.remove(injection.id());
                } else {
                    injections.put(injection.id(), injection.withAmount(remaining));
                }
                setDirty();
                continue;
            }
            MagicMachineResourceRecovery.Result result = MagicMachineResourceRecovery.recover(
                    server, injection.kind(), injection.dimension(), injection.position(),
                    injection.amount());
            if (result.status() == MagicMachineResourceRecovery.Status.UNAVAILABLE) continue;
            if (result.status() == MagicMachineResourceRecovery.Status.NO_TARGET) {
                injections.put(injection.id(), injection.unknown());
                pendingRecovery.remove(injection.id());
                archive(injection.taskId(), injection.kind(), injection.amount(),
                        -1, PendingReason.NO_TARGET, gameTime(server));
                setDirty();
                continue;
            }
            int extracted = result.amount();
            int accepted = extracted <= 0 ? 0
                    : MagicResourceEndpoint.insert(server, injection.kind(), extracted, true);
            int diskPending = Math.max(0, extracted - accepted);
            int machineRemaining = Math.max(0, injection.amount() - extracted);
            if (diskPending == 0 && machineRemaining == 0) {
                injections.remove(injection.id());
                pendingRecovery.remove(injection.id());
            } else {
                if (diskPending > 0) {
                    injections.put(injection.id(), injection.withAmount(diskPending)
                            .withState(InjectionState.DISK_PENDING));
                } else {
                    injections.remove(injection.id());
                    pendingRecovery.remove(injection.id());
                }
                if (machineRemaining > 0) {
                    UUID unresolvedId = UUID.randomUUID();
                    injections.put(unresolvedId, new Injection(unresolvedId, injection.taskId(),
                            injection.kind(), injection.dimension(), injection.position(),
                            machineRemaining, InjectionState.UNKNOWN));
                    pendingRecovery.remove(unresolvedId);
                    archive(injection.taskId(), injection.kind(), machineRemaining,
                            -1, PendingReason.MACHINE_PARTIAL, gameTime(server));
                }
            }
            setDirty();
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Magic] Reload recovery task={} kind={} recovered={} diskPending={} unknown={} at={} {}",
                    injection.taskId(), injection.kind().id(), accepted, diskPending, machineRemaining,
                    injection.dimension(), injection.position());
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag serializedTasks = new ListTag();
        for (Map.Entry<UUID, Map<MagicResourceKind, Integer>> entry : tasks.entrySet()) {
            CompoundTag task = new CompoundTag();
            task.putUUID("id", entry.getKey());
            CompoundTag resources = new CompoundTag();
            entry.getValue().forEach((kind, amount) -> resources.putInt(kind.id(), amount));
            task.put("resources", resources);
            serializedTasks.add(task);
        }
        tag.put("tasks", serializedTasks);

        ListTag serializedInjections = new ListTag();
        for (Injection injection : injections.values()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("id", injection.id());
            value.putUUID("task", injection.taskId());
            value.putString("kind", injection.kind().name());
            value.putString("dimension", injection.dimension().toString());
            value.putLong("position", injection.position().asLong());
            value.putInt("amount", injection.amount());
            value.putString("state", injection.state().name());
            serializedInjections.add(value);
        }
        tag.put("injections", serializedInjections);

        ListTag serializedPending = new ListTag();
        for (Pending pending : pendingAudits.values()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("id", pending.id());
            if (pending.taskId() != null) value.putUUID("task", pending.taskId());
            value.putString("kind", pending.kind().name());
            value.putInt("amount", pending.amount());
            value.putInt("bdNetwork", pending.bdNetworkId());
            value.putString("reason", pending.reason().name());
            value.putLong("at", pending.recordedAt());
            serializedPending.add(value);
        }
        tag.put("pendingAudits", serializedPending);
        return tag;
    }

    /** 记录时间用主世界游戏刻；0 表示建服前产生，读取时按 0 处理。 */
    private long gameTime(MinecraftServer server) {
        return server == null ? 0L : server.overworld().getGameTime();
    }

    /** 淘汰超过保留期的待核对记录；只在有记录时做一次扫描。 */
    private void prunePendingAuditsByAge(long now) {
        if (pendingAudits.isEmpty()) return;
        boolean removed = false;
        for (UUID id : List.copyOf(pendingAudits.keySet())) {
            Pending entry = pendingAudits.get(id);
            if (entry != null && now - entry.recordedAt() > PENDING_RETENTION_TICKS) {
                pendingAudits.remove(id);
                removed = true;
            }
        }
        if (removed) setDirty();
    }

    @Nullable
    private static Injection readInjection(CompoundTag tag) {
        if (!tag.hasUUID("id") || !tag.hasUUID("task")) return null;
        MagicResourceKind kind;
        ResourceLocation dimension;
        InjectionState state;
        try {
            kind = MagicResourceKind.valueOf(tag.getString("kind"));
            dimension = ResourceLocation.tryParse(tag.getString("dimension"));
            state = InjectionState.valueOf(tag.getString("state"));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (dimension == null || tag.getInt("amount") <= 0) return null;
        return new Injection(tag.getUUID("id"), tag.getUUID("task"), kind, dimension,
                BlockPos.of(tag.getLong("position")), tag.getInt("amount"), state);
    }

    @Nullable
    private static Pending readPending(CompoundTag tag) {
        if (!tag.hasUUID("id")) return null;
        MagicResourceKind kind;
        PendingReason reason;
        try {
            kind = MagicResourceKind.valueOf(tag.getString("kind"));
            reason = PendingReason.valueOf(tag.getString("reason"));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (tag.getInt("amount") <= 0) return null;
        return new Pending(tag.getUUID("id"),
                tag.hasUUID("task") ? tag.getUUID("task") : null,
                kind, tag.getInt("amount"), tag.getInt("bdNetwork"), reason, tag.getLong("at"));
    }
}
