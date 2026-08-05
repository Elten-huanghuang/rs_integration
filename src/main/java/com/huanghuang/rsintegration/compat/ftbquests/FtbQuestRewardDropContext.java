package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.util.ThreadLocalStack;

/**
 * Marks the synchronous FTB Quest claim-all reward operation.
 *
 * <p>FTB claims run on the server thread. A stack, rather than a boolean,
 * keeps nested claims well-defined and restores the previous state on exit.</p>
 */
public final class FtbQuestRewardDropContext {

    private static final ThreadLocalStack<Boolean> ACTIVE = new ThreadLocalStack<>();

    private FtbQuestRewardDropContext() {}

    public static Scope activate() {
        ACTIVE.push(Boolean.TRUE);
        return new Scope();
    }

    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.peek());
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {}

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            ACTIVE.pop();
        }
    }
}
