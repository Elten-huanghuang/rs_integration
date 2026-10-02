package com.huanghuang.rsintegration.compat.ftbquests;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** 保存分批任务扫描的游标，预算耗尽后在下一个 tick 继续。 */
final class QuestTaskScan {

    private final List<Long> taskIds;
    private int cursor;

    QuestTaskScan(List<Long> taskIds) {
        this.taskIds = List.copyOf(taskIds);
    }

    int processBatch(int allowance, BooleanSupplier hasTime, LongConsumer scanTask) {
        int attempted = 0;
        while (!finished() && attempted < allowance && hasTime.getAsBoolean()) {
            long taskId = taskIds.get(cursor++);
            attempted++;
            scanTask.accept(taskId);
        }
        return attempted;
    }

    boolean finished() {
        return cursor >= taskIds.size();
    }

    void cancel() {
        cursor = taskIds.size();
    }
}
