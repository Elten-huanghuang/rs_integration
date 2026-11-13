package com.huanghuang.rsintegration.crafting.progress;

/** 已观察到的机器本轮进度；只汇总能确认归属于当前操作的计时。 */
public record MachineCycleProgress(long completedTicks, long totalTicks, int reportingMachines, int activeMachines) {
    public static final MachineCycleProgress UNKNOWN = new MachineCycleProgress(0, 0, 0, 0);

    public MachineCycleProgress {
        if (completedTicks < 0 || totalTicks < completedTicks || reportingMachines < 0
                || activeMachines < reportingMachines || (totalTicks == 0) != (reportingMachines == 0)) {
            throw new IllegalArgumentException("invalid machine cycle progress");
        }
    }

    public static MachineCycleProgress of(long completed, long total) {
        return total <= 0 || completed < 0 || completed > total ? UNKNOWN
                : new MachineCycleProgress(completed, total, 1, 1);
    }

    public boolean known() { return totalTicks > 0; }
    public int percent() { return known() ? (int) Math.min(100, 100.0 * completedTicks / totalTicks) : 0; }
}
