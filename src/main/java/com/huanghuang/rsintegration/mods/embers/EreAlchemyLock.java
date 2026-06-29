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
        return TimeUnit.MINUTES.toMillis(RSIntegrationConfig.EMBERS_LOCK_TIMEOUT_MINUTES.get());
    }

    private record LockEntry(UUID playerId, long timestamp) {}

    private static final Map<GlobalPos, LockEntry> LOCKS = new ConcurrentHashMap<>();

    /**
     * Try to acquire the lock at {@code (dim, pos)} for {@code playerId}.
     * If an existing lock has exceeded {@link #getTtlMs()} it is evicted first.
     *
     * @return {@code true} if the lock was acquired (or an expired lock was
     *         replaced), {@code false} if the lock is still held by another player.
     */
    public static boolean tryLock(ResourceKey<Level> dim, BlockPos pos, UUID playerId) {
        GlobalPos gp = GlobalPos.of(dim, pos);
        LockEntry existing = LOCKS.get(gp);
        if (existing != null) {
            if (System.currentTimeMillis() - existing.timestamp > getTtlMs()) {
                // Expired — atomically remove only if it is still the same entry.
                LOCKS.remove(gp, existing);
                // Fall through to acquire below.
            } else {
                return false;
            }
        }
        return LOCKS.putIfAbsent(gp, new LockEntry(playerId, System.currentTimeMillis())) == null;
    }

    public static void unlock(ResourceKey<Level> dim, BlockPos pos) {
        LOCKS.remove(GlobalPos.of(dim, pos));
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
            if (now - entry.getValue().timestamp > getTtlMs()) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private EreAlchemyLock() {}
}
