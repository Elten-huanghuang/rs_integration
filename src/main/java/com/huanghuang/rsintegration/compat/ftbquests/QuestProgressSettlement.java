package com.huanghuang.rsintegration.compat.ftbquests;

final class QuestProgressSettlement {
    private QuestProgressSettlement() {}

    static boolean shouldRefundRejectedProgress(long accepted) {
        return accepted <= 0L;
    }
}
