package com.huanghuang.rsintegration.crafting.planning;

import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Marks planner workers so world, network and third-party reflection access can fail closed. */
public final class PlanningThreadContext {
    private static final ThreadLocal<Boolean> BACKGROUND = ThreadLocal.withInitial(() -> false);

    private PlanningThreadContext() {}

    static <T> T runInBackground(Supplier<T> work) {
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

    public static final class MainThreadPlanningFallbackException extends RuntimeException {
        public MainThreadPlanningFallbackException(String operation) {
            super(operation + " requires the server thread");
        }
    }
}
