package com.huanghuang.rsintegration.mods.ironfurnaces;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 工厂通道租约；独立于存储后端，供熔炉原生分配逻辑查询。 */
public final class IronFurnaceFactoryLeases {
    static final Map<String, boolean[]> LANES = new ConcurrentHashMap<>();

    private IronFurnaceFactoryLeases() {}

    static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + ":" + pos.asLong();
    }

    public static boolean hasActiveLease(BlockEntity furnace) {
        if (!(furnace.getLevel() instanceof ServerLevel level)) return false;
        boolean[] leases = LANES.get(key(level, furnace.getBlockPos()));
        if (leases == null) return false;
        synchronized (leases) {
            for (boolean leased : leases) {
                if (leased) return true;
            }
        }
        return false;
    }
}
