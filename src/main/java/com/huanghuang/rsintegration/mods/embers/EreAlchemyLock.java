package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * GlobalPos-based mutex preventing concurrent tablet access across delegates.
 *
 * <p>Locks carry a creation timestamp and automatically expire after
 * {@link #getTtlMs()} so that a crashed or disconnected player cannot
 * permanently lock a tablet.
 */
public final class EreAlchemyLock {

    private static long getTtlMs() {
        try {
            return TimeUnit.MINUTES.toMillis(RSIntegrationConfig.EMBERS_LOCK_TIMEOUT_MINUTES.get());
        } catch (Exception ignored) {
            return TimeUnit.MINUTES.toMillis(10);
        }
    }

    public record Lease(GlobalPos position, UUID ownerId, long generation) {}

    private record LockEntry(UUID ownerId, long generation, long timestamp) {}

    private static final Map<GlobalPos, LockEntry> LOCKS = new ConcurrentHashMap<>();
    private static long nextGeneration = 1L;

    /**
     * Try to acquire the lock at {@code (dim, pos)} for {@code playerId}.
     * If an existing lock has exceeded {@link #getTtlMs()} it is evicted first.
     *
     * @return {@code true} if the lock was acquired (or an expired lock was
     *         replaced), {@code false} if the lock is still held by another player.
     */
    public static synchronized Lease tryAcquire(ResourceKey<Level> dim, BlockPos pos, UUID ownerId) {
        GlobalPos gp = GlobalPos.of(dim, pos);
        LockEntry existing = LOCKS.get(gp);
        if (existing != null) {
            if (System.currentTimeMillis() - existing.timestamp() <= getTtlMs()) return null;
            LOCKS.remove(gp, existing);
        }
        long generation = nextGeneration++;
        LOCKS.put(gp, new LockEntry(ownerId, generation, System.currentTimeMillis()));
        return new Lease(gp, ownerId, generation);
    }

    public static boolean release(Lease lease) {
        if (lease == null) return false;
        LockEntry actual = LOCKS.get(lease.position());
        if (actual == null || !actual.ownerId().equals(lease.ownerId())
                || actual.generation() != lease.generation()) return false;
        return LOCKS.remove(lease.position(), actual);
    }

    /** @deprecated use {@link #tryAcquire(ResourceKey, BlockPos, UUID)}. */
    @Deprecated
    public static boolean tryLock(ResourceKey<Level> dim, BlockPos pos, UUID playerId) {
        return tryAcquire(dim, pos, playerId) != null;
    }

    /** @deprecated ownerless unlock is unsafe and retained only for source compatibility. */
    @Deprecated
    public static void unlock(ResourceKey<Level> dim, BlockPos pos) {
        // Intentionally do nothing: an ownerless caller could delete another craft's lease.
    }

    public static int clearAll() {
        int count = LOCKS.size();
        LOCKS.clear();
        return count;
    }

    /**
     * Remove every lock whose age exceeds {@link #getTtlMs()}.
     *
     * @return number of expired locks removed.
     */
    public static int cleanExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<GlobalPos, LockEntry>> it = LOCKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<GlobalPos, LockEntry> entry = it.next();
            if (now - entry.getValue().timestamp() > getTtlMs()) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private EreAlchemyLock() {}
}
