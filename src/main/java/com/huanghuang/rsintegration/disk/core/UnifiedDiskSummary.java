package com.huanghuang.rsintegration.disk.core;

/** 展示用摘要，不参与库存读写；汇总允许超过单种资源的 int 上限。 */
public record UnifiedDiskSummary(long items, long fluids, int itemTypes, int fluidTypes,
                                 int itemCapacity, int fluidCapacity) {
    public UnifiedDiskSummary {
        if (itemCapacity < 1 || itemCapacity > 262144 || fluidCapacity < 1 || fluidCapacity > 262144
                || itemTypes < 0 || itemTypes > itemCapacity || fluidTypes < 0 || fluidTypes > fluidCapacity
                || items < itemTypes || items > (long) itemTypes * Integer.MAX_VALUE
                || fluids < fluidTypes || fluids > (long) fluidTypes * Integer.MAX_VALUE) {
            throw new IllegalArgumentException("统一盘展示摘要非法");
        }
    }

    public static UnifiedDiskSummary from(UnifiedDiskCore core) {
        core.checkThread();
        return new UnifiedDiskSummary(core.items.displayTotal(), core.fluids.displayTotal(),
                core.items.size(), core.fluids.size(), core.limits.items(), core.limits.fluids());
    }
}
