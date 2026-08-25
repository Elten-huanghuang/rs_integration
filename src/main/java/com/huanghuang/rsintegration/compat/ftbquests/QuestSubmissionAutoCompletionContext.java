package com.huanghuang.rsintegration.compat.ftbquests;

/** Defers FTB's automatic reward/reset callback until submitted items settle. */
public final class QuestSubmissionAutoCompletionContext {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private QuestSubmissionAutoCompletionContext() {}

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
