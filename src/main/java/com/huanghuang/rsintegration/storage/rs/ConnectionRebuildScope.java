package com.huanghuang.rsintegration.storage.rs;

/** 只在节点状态稳定后的排队动作中去重，不把时间相同当成状态相同。 */
public final class ConnectionRebuildScope {
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    private ConnectionRebuildScope() {}

    public static void execute(Object itemCache, Object fluidCache, Runnable actions) {
        Session previous = CURRENT.get();
        CURRENT.set(new Session(itemCache, fluidCache));
        try {
            actions.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                // 嵌套网络重建可能改变外层状态，恢复作用域时允许再次刷新。
                previous.itemsReady = false;
                previous.fluidsReady = false;
                CURRENT.set(previous);
            }
        }
    }

    public static boolean alreadyRebuilt(Object cache) {
        Session session = CURRENT.get();
        return session != null && ((cache == session.items && session.itemsReady)
                || (cache == session.fluids && session.fluidsReady));
    }

    public static void record(Object cache, boolean complete) {
        Session session = CURRENT.get();
        if (session == null) return;
        if (cache == session.items) session.itemsReady = complete;
        if (cache == session.fluids) session.fluidsReady = complete;
    }

    private static final class Session {
        private final Object items;
        private final Object fluids;
        private boolean itemsReady;
        private boolean fluidsReady;

        private Session(Object items, Object fluids) {
            this.items = items;
            this.fluids = fluids;
        }
    }
}
