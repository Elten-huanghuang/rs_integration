package com.huanghuang.rsintegration.magic;

import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次机器操作持有的魔法资源预算。
 *
 * <p>磁盘先承担差额并预留，机器本体余额只用于补足不足部分。资源真正写入
 * 原生机器后才减少预留；任务失败时尽量把尚未消耗的磁盘资源收回。</p>
 */
public final class MagicCraftingEnergyTask implements AutoCloseable {
    /** 原生机器的最小读写边界。 */
    public interface Target {
        int current();

        /** 返回目标实际接收的数量。 */
        int accept(int amount);

        /** 返回目标实际移除的数量。 */
        int extract(int amount);

        /** 可持久化的机器位置；无法定位时返回 null。 */
        @Nullable
        default MagicResourceTaskReservation.RecoveryLocation recoveryLocation() {
            return null;
        }
    }

    private final MinecraftServer server;
    private final MagicResourceKind kind;
    private final int total;
    @Nullable
    private MagicResourceNetworkPool reservation;
    @Nullable
    private final MagicResourceTaskReservation taskReservation;
    private final boolean ownsReservation;
    @Nullable
    private Target target;
    @Nullable
    private UUID injectionId;
    private int injected;
    private boolean prepared;
    private boolean completed;
    private boolean recovered;

    private MagicCraftingEnergyTask(MinecraftServer server, MagicResourceKind kind, int total,
                                    @Nullable MagicResourceTaskReservation taskReservation,
                                    boolean ownsReservation) {
        this.server = server;
        this.kind = kind;
        this.total = total;
        this.taskReservation = taskReservation;
        this.ownsReservation = ownsReservation;
    }

    /** 为一次固定费用操作创建预算；无需资源时返回 null。 */
    @Nullable
    public static MagicCraftingEnergyTask reserve(
            @Nullable MinecraftServer server, MagicResourceKind kind, int total) {
        if (server == null || kind == null || total <= 0
                || !kind.supportsRsiRecursiveCrafting()) return null;
        return new MagicCraftingEnergyTask(server, kind, total, null, true);
    }

    /** 从递归任务级共享预留中取得本节点的预算。 */
    @Nullable
    public static MagicCraftingEnergyTask reserve(
            @Nullable MinecraftServer server, MagicResourceKind kind, int total,
            @Nullable MagicResourceTaskReservation taskReservation) {
        if (server == null || kind == null || total <= 0
                || !kind.supportsRsiRecursiveCrafting()) {
            return null;
        }
        if (taskReservation == null) {
            return reserve(server, kind, total);
        }
        return new MagicCraftingEnergyTask(server, kind, total, taskReservation, false);
    }

    public MagicResourceKind kind() {
        return kind;
    }

    public int total() {
        return total;
    }

    public int injected() {
        return injected;
    }

    public boolean prepared() {
        return prepared;
    }

    /**
     * 幂等检查并补足机器余额。目标容量暂时不足时返回 false，调用方可以稍后重试。
     */
    public boolean tryPrepare(Target target) {
        Objects.requireNonNull(target, "target");
        this.target = target;
        if (completed || recovered) return false;
        if (prepared) return true;
        if (reservation == null) {
            reservation = taskReservation != null
                    ? taskReservation.pool(kind)
                    : MagicResourceEndpoint.reserve(server, kind, total);
        }

        int current = Math.max(0, target.current());
        long available = (long) current + reservation.remaining();
        if (available < total) return false;

        // 只提取本次节点真正缺少的部分，剩余预留留给后续节点；原机容量不足时，
        // 未接受部分会退回预留，不会把整份任务预算抽走。
        int wanted = Math.min(Math.max(0, total - current), reservation.remaining());
        if (wanted <= 0) {
            prepared = true;
            return true;
        }

        int withdrawn = reservation.withdraw(wanted);
        if (withdrawn <= 0) return false;
        int accepted = Math.max(0, Math.min(withdrawn, target.accept(withdrawn)));
        if (accepted < withdrawn) reservation.refund(withdrawn - accepted);
        injected += accepted;
        if (accepted > 0 && taskReservation != null && injectionId == null) {
            injectionId = taskReservation.recordInjection(
                    kind, target.recoveryLocation(), accepted);
        }

        if (current + accepted < total) {
            recoverInjected();
            return false;
        }
        prepared = true;
        return true;
    }

    /** 原机完成消费后提交账本，不回收机器内余额。 */
    public void complete() {
        if (completed || recovered) return;
        completed = true;
        if (taskReservation != null) taskReservation.injectedConsumed(injectionId);
        releaseReservation();
    }

    /** 失败或取消时回收能确认仍在目标中的磁盘注入量。 */
    public void recover() {
        if (completed || recovered) return;
        recovered = true;
        recoverInjected();
        if (taskReservation != null && injectionId != null) {
            taskReservation.injectedRecovered(injectionId, injected);
        }
        releaseReservation();
    }

    private void recoverInjected() {
        if (target == null || injected <= 0) return;
        int reclaimable = Math.min(injected, Math.max(0, target.current()));
        if (reclaimable <= 0) return;
        int extracted = Math.max(0, Math.min(reclaimable, target.extract(reclaimable)));
        if (extracted > 0 && reservation != null) reservation.refund(extracted);
        injected = Math.max(0, injected - extracted);
    }

    private void releaseReservation() {
        if (reservation == null) return;
        if (ownsReservation) reservation.close();
        reservation = null;
    }

    @Override
    public void close() {
        if (!completed) recover();
    }
}
