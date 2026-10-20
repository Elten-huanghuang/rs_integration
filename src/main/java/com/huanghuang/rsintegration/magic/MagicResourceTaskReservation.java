package com.huanghuang.rsintegration.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 一次递归合成任务持有的共享磁盘预留。 */
public final class MagicResourceTaskReservation implements AutoCloseable {
    private static final MagicResourceNetworkPool EMPTY = new MagicResourceNetworkPool() {
        @Override public long remaining() { return 0; }
        @Override public long withdraw(long requested) { return 0; }
        @Override public void refund(long amount) { }
        @Override public void close() { }
    };

    private final MinecraftServer server;
    private final UUID taskId;
    private final MagicResourceTaskLedger ledger;
    private final Map<MagicResourceKind, MagicResourceNetworkPool> pools =
            new EnumMap<>(MagicResourceKind.class);
    private final Map<MagicResourceKind, Long> reserved = new EnumMap<>(MagicResourceKind.class);
    private boolean closed;

    private MagicResourceTaskReservation(MinecraftServer server, UUID taskId,
                                         MagicResourceTaskLedger ledger) {
        this.server = server;
        this.taskId = taskId;
        this.ledger = ledger;
    }

    @Nullable
    public static MagicResourceTaskReservation reserve(
            @Nullable MinecraftServer server, UUID taskId, List<MagicResourceCost> costs) {
        if (server == null || taskId == null || costs == null || costs.isEmpty()) return null;
        List<MagicResourceCost> supported = new ArrayList<>();
        for (MagicResourceCost cost : MagicResourceCost.merge(costs)) {
            if (cost.kind().supportsRsiRecursiveCrafting()) supported.add(cost);
        }
        if (supported.isEmpty()) return null;
        MagicResourceTaskReservation reservation =
                new MagicResourceTaskReservation(server, taskId, MagicResourceTaskLedger.get(server));
        List<MagicResourceCost> actual = new ArrayList<>();
        for (MagicResourceCost cost : supported) {
            MagicResourceNetworkPool pool = MagicResourceEndpoint.reserve(
                    server, cost.kind(), cost.amountPerOperation());
            long claimed = Math.max(0, pool.remaining());
            reservation.pools.put(cost.kind(), pool);
            reservation.reserved.put(cost.kind(), claimed);
            if (claimed > 0) actual.add(new MagicResourceCost(cost.kind(), claimed));
        }
        reservation.ledger.recordTask(taskId, actual);
        return reservation;
    }

    public UUID taskId() {
        return taskId;
    }

    public long reserved(MagicResourceKind kind) {
        return reserved.getOrDefault(kind, 0L);
    }

    public MagicResourceNetworkPool pool(MagicResourceKind kind) {
        return closed ? EMPTY : pools.getOrDefault(kind, EMPTY);
    }

    public UUID recordInjection(MagicResourceKind kind, @Nullable RecoveryLocation location, long amount) {
        if (closed || location == null || amount <= 0) return null;
        return ledger.recordInjection(taskId, kind, location.dimension(), location.position(), amount);
    }

    public void injectedConsumed(@Nullable UUID injectionId) {
        if (!closed) ledger.consumed(injectionId);
    }

    public void injectedRecovered(@Nullable UUID injectionId, long remaining) {
        if (!closed && injectionId != null) ledger.recovered(injectionId, remaining);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (MagicResourceNetworkPool pool : pools.values()) {
            pool.close();
        }
        pools.clear();
        ledger.taskClosed(taskId);
    }

    public record RecoveryLocation(ResourceLocation dimension, BlockPos position) {
        public RecoveryLocation {
            position = position.immutable();
        }
    }
}
