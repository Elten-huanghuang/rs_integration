package com.huanghuang.rsintegration.crafting.planning;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Marks planner workers so world, network and third-party reflection access can fail closed. */
public final class PlanningThreadContext {
    private static final ThreadLocal<Boolean> BACKGROUND = ThreadLocal.withInitial(() -> false);

    private PlanningThreadContext() {}

    public static <T> T runInBackground(Supplier<T> work) {
        if (BACKGROUND.get()) return work.get();
        BACKGROUND.set(true);
        try {
            return work.get();
        } finally {
            BACKGROUND.remove();
        }
    }

    public static boolean isBackgroundPlanningThread() {
        return BACKGROUND.get();
    }

    static void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Pure recipe planning interrupted");
        }
    }

    public static void requireMainThread(String operation) {
        if (isBackgroundPlanningThread()) {
            throw new MainThreadPlanningFallbackException(operation);
        }
    }

    /** 在读取世界对象之前检查线程，索引采集不能只依赖后台规划标记。 */
    public static MinecraftServer requireServerThread(String operation) {
        requireMainThread(operation);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) {
            throw new MainThreadPlanningFallbackException(operation);
        }
        return server;
    }

    public static final class MainThreadPlanningFallbackException extends RuntimeException {
        public MainThreadPlanningFallbackException(String operation) {
            super(operation + " requires the server thread");
        }
    }
}
