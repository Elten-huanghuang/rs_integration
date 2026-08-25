package com.huanghuang.rsintegration.compat.ftbquests;

final class QuestProgressSettlement {
    private QuestProgressSettlement() {}

    /**
     * Computes the amount that the task is allowed to accept before FTB's
     * completion callbacks run.  Those callbacks may auto-claim rewards and
     * reset repeatable tasks, so the post-call progress value is not a stable
     * measure of the accepted amount.
     */
    static long expectedAccepted(long before, long maxProgress, long offered) {
        if (before < 0L || maxProgress <= before || offered <= 0L) return 0L;
        return Math.min(offered, maxProgress - before);
    }

    static boolean shouldRefundRejectedProgress(long accepted) {
        return accepted <= 0L;
    }
}
