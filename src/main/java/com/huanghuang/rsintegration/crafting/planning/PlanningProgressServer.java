package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe request-level progress registry. Worker threads only mutate snapshots. */
public final class PlanningProgressServer {
    private static final long PUBLISH_INTERVAL_NANOS = 100_000_000L;
    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();

    private PlanningProgressServer() {}

    public static void begin(UUID playerId, long requestId, long generation,
                             ResourceLocation recipeId) {
        if (playerId == null || requestId <= 0L || generation <= 0L || recipeId == null) return;
        ACTIVE.put(playerId, new Active(requestId, generation, recipeId));
    }

    public static void warmingUp(UUID playerId, long generation) {
        update(playerId, generation, PlanningProgressSnapshot.State.WARMING_UP,
                PlanningProgressSnapshot.Phase.CATALOG, Component.empty());
    }

    public static void queued(UUID playerId, long generation,
                              PlanningProgressSnapshot.Phase phase) {
        update(playerId, generation, PlanningProgressSnapshot.State.QUEUED,
                phase, Component.empty());
    }

    public static void running(UUID playerId, long generation,
                               PlanningProgressSnapshot.Phase phase) {
        update(playerId, generation, PlanningProgressSnapshot.State.RUNNING,
                phase, Component.empty());
    }

    public static void finalizing(UUID playerId, long generation) {
        update(playerId, generation, PlanningProgressSnapshot.State.FINALIZING,
                PlanningProgressSnapshot.Phase.FINALIZING, Component.empty());
    }

    public static void succeed(ServerPlayer player, long generation) {
        finish(player, generation, PlanningProgressSnapshot.State.SUCCEEDED,
                PlanningProgressSnapshot.Phase.COMPLETE, Component.empty());
    }

    public static void fail(ServerPlayer player, long generation, Component detail) {
        if (player == null) return;
        finish(player, generation, failureState(detail),
                currentPhase(player.getUUID()), detail);
    }

    public static void failDirect(ServerPlayer player, long requestId, ResourceLocation recipeId,
                                  Component detail) {
        if (player == null || requestId <= 0L) return;
        PlanningProgressSnapshot snapshot = new PlanningProgressSnapshot(requestId, 0L,
                recipeId, PlanningProgressSnapshot.State.FAILED,
                PlanningProgressSnapshot.Phase.ACCEPTING, 0L, detail);
        send(player, snapshot);
    }

    public static void tick(MinecraftServer server) {
        long now = System.nanoTime();
        for (Map.Entry<UUID, Active> entry : ACTIVE.entrySet()) {
            Active active = entry.getValue();
            if (!active.dirty() || now - active.lastSentNanos < PUBLISH_INTERVAL_NANOS) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.hasDisconnected() || player.isRemoved()) continue;
            long sendingVersion = active.version();
            send(player, active.snapshot());
            active.markSent(now, sendingVersion);
        }
    }

    public static void remove(UUID playerId) {
        if (playerId != null) ACTIVE.remove(playerId);
    }

    public static void clear() {
        ACTIVE.clear();
    }

    private static void finish(ServerPlayer player, long generation,
                               PlanningProgressSnapshot.State state,
                               PlanningProgressSnapshot.Phase phase, Component detail) {
        if (player == null) return;
        Active active = ACTIVE.get(player.getUUID());
        if (active == null || active.generation != generation) return;
        active.update(state, phase, detail);
        send(player, active.snapshot());
        ACTIVE.remove(player.getUUID(), active);
    }

    private static void update(UUID playerId, long generation,
                               PlanningProgressSnapshot.State state,
                               PlanningProgressSnapshot.Phase phase, Component detail) {
        Active active = ACTIVE.get(playerId);
        if (active != null && active.generation == generation) active.update(state, phase, detail);
    }

    private static PlanningProgressSnapshot.Phase currentPhase(UUID playerId) {
        Active active = ACTIVE.get(playerId);
        return active == null ? PlanningProgressSnapshot.Phase.ACCEPTING
                : active.value.get().phase();
    }

    static PlanningProgressSnapshot.State failureState(Component detail) {
        if (detail != null && detail.getContents() instanceof TranslatableContents translated) {
            String key = translated.getKey();
            if (key.contains("timeout") || key.contains("complexity_limit")) {
                return PlanningProgressSnapshot.State.TIMED_OUT;
            }
        }
        return PlanningProgressSnapshot.State.FAILED;
    }

    private static void send(ServerPlayer player, PlanningProgressSnapshot snapshot) {
        if (player == null || player.hasDisconnected() || player.isRemoved()) return;
        BatchCraftNetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PlanningProgressPacket(snapshot));
    }

    private static final class Active {
        private final long requestId;
        private final long generation;
        private final ResourceLocation recipeId;
        private final long startedNanos = System.nanoTime();
        private final AtomicReference<PlanningProgressSnapshot> value;
        private final AtomicLong version = new AtomicLong(1L);
        private volatile long sentVersion;
        private volatile long lastSentNanos;

        private Active(long requestId, long generation, ResourceLocation recipeId) {
            this.requestId = requestId;
            this.generation = generation;
            this.recipeId = recipeId;
            this.value = new AtomicReference<>(snapshot(PlanningProgressSnapshot.State.ACCEPTED,
                    PlanningProgressSnapshot.Phase.ACCEPTING, Component.empty()));
        }

        private void update(PlanningProgressSnapshot.State state,
                            PlanningProgressSnapshot.Phase phase, Component detail) {
            value.set(snapshot(state, phase, detail));
            version.incrementAndGet();
        }

        private PlanningProgressSnapshot snapshot(PlanningProgressSnapshot.State state,
                                                  PlanningProgressSnapshot.Phase phase,
                                                  Component detail) {
            return new PlanningProgressSnapshot(requestId, generation, recipeId, state, phase,
                    Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L), detail);
        }

        private PlanningProgressSnapshot snapshot() {
            PlanningProgressSnapshot current = value.get();
            return snapshot(current.state(), current.phase(), current.detail());
        }

        private boolean dirty() {
            return version.get() != sentVersion;
        }

        private long version() {
            return version.get();
        }

        private void markSent(long now, long sendingVersion) {
            sentVersion = sendingVersion;
            lastSentNanos = now;
        }
    }
}
