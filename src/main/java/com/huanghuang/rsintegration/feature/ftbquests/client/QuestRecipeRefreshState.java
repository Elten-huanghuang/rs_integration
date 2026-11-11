package com.huanghuang.rsintegration.feature.ftbquests.client;

/** 等任务文件和队伍数据同步完成后刷新，并合并连续的进度更新。 */
final class QuestRecipeRefreshState {
    private static final int DEBOUNCE_TICKS = 20;
    private static final int MAX_DELAY_TICKS = 100;

    private Object file;
    private Object teamData;
    private boolean waiting = true;
    private int delay;
    private int ticksWaiting;

    void requestRefresh() {
        if (!waiting) ticksWaiting = 0;
        waiting = true;
        delay = DEBOUNCE_TICKS;
    }

    boolean tick(Object currentFile, Object currentTeamData, boolean loading) {
        if (file != currentFile || teamData != currentTeamData) {
            file = currentFile;
            teamData = currentTeamData;
            waiting = true;
            delay = 0;
            ticksWaiting = 0;
        }
        // 同步期间的空列表不能当作一次成功刷新，否则之后不会再补齐条目。
        if (currentFile == null || currentTeamData == null || loading) {
            waiting = true;
            return false;
        }
        return waiting && (++ticksWaiting >= MAX_DELAY_TICKS || --delay <= 0);
    }

    void refreshed() {
        waiting = false;
        ticksWaiting = 0;
    }
}
