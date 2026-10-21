package com.huanghuang.rsintegration.storage.disk.rs;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import net.minecraft.server.MinecraftServer;

/** 所有磁盘操纵器共享的单 tick 时间预算。 */
public final class DiskManipulatorBudget {
    private static long tick = Long.MIN_VALUE;
    private static long deadline;

    private DiskManipulatorBudget() {}

    public static boolean hasTime(MinecraftServer server) {
        if (server == null) return false;
        int budgetMicros = RSStorageConfig.diskManipulatorBudgetMicros();
        if (budgetMicros <= 0) return false;
        long nowTick = server.getTickCount();
        if (nowTick != tick) {
            tick = nowTick;
            deadline = System.nanoTime() + budgetMicros * 1000L;
        }
        return System.nanoTime() < deadline;
    }

    public static void reset() {
        tick = Long.MIN_VALUE;
        deadline = 0L;
    }
}
