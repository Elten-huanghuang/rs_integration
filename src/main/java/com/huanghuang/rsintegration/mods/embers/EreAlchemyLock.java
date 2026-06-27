package com.huanghuang.rsintegration.mods.embers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GlobalPos-based mutex preventing concurrent tablet access across delegates.
 */
public final class EreAlchemyLock {

    private static final Map<GlobalPos, UUID> LOCKS = new ConcurrentHashMap<>();

    public static boolean tryLock(ResourceKey<Level> dim, BlockPos pos, UUID playerId) {
        return LOCKS.putIfAbsent(GlobalPos.of(dim, pos), playerId) == null;
    }

    public static void unlock(ResourceKey<Level> dim, BlockPos pos) {
        LOCKS.remove(GlobalPos.of(dim, pos));
    }

    public static int clearAll() {
        int count = LOCKS.size();
        LOCKS.clear();
        return count;
    }

    private EreAlchemyLock() {}
}
