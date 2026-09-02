package com.huanghuang.rsintegration.compat.ftbquests;

/** Suppresses only inventory-listener re-entry caused by RSI's own item settlement. */
public final class QuestInventorySubmissionContext {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private QuestInventorySubmissionContext() {}

    public static Scope open() {
        DEPTH.set(DEPTH.get() + 1);
        return new Scope();
    }

    public static boolean isSuppressed() {
        return DEPTH.get() > 0;
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {}

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            int depth = DEPTH.get() - 1;
            if (depth <= 0) DEPTH.remove();
            else DEPTH.set(depth);
        }
    }
}
