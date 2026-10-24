package com.huanghuang.rsintegration.feature.ftbquests;

/** 决定原生物品任务提交失败或部分成功后是否继续递归补料。 */
final class NativeSubmissionRecoveryPolicy {
    private NativeSubmissionRecoveryPolicy() {}

    static boolean shouldCraftRemainder(
            boolean handled, boolean transactionSettled, long remaining) {
        return remaining > 0L && (!handled || transactionSettled);
    }
}
