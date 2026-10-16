package com.huanghuang.rsintegration.disk.core;

import com.huanghuang.rsintegration.magic.MagicResourceKind;

import java.util.Arrays;

/** 展示用摘要，不参与库存读写；汇总允许超过单种资源的 int 上限。 */
public record UnifiedDiskSummary(long items, long fluids, int[] magicAmounts, int itemTypes, int fluidTypes,
                                 int itemCapacity, int fluidCapacity) {
    public UnifiedDiskSummary {
        magicAmounts = magicAmounts.clone();
        if (itemCapacity < 1 || itemCapacity > 262144 || fluidCapacity < 1 || fluidCapacity > 262144
                || itemTypes < 0 || itemTypes > itemCapacity || fluidTypes < 0 || fluidTypes > fluidCapacity
                || items < itemTypes || items > (long) itemTypes * Integer.MAX_VALUE
                || fluids < fluidTypes || fluids > (long) fluidTypes * Integer.MAX_VALUE
                || magicAmounts.length != MagicResourceKind.values().length) {
            throw new IllegalArgumentException("统一盘展示摘要非法");
        }
        for (int amount : magicAmounts) {
            if (amount < 0) throw new IllegalArgumentException("统一盘魔法资源数量非法");
        }
    }

    @Override public int[] magicAmounts() { return magicAmounts.clone(); }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof UnifiedDiskSummary summary
                && items == summary.items
                && fluids == summary.fluids
                && Arrays.equals(magicAmounts, summary.magicAmounts)
                && itemTypes == summary.itemTypes
                && fluidTypes == summary.fluidTypes
                && itemCapacity == summary.itemCapacity
                && fluidCapacity == summary.fluidCapacity;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(items);
        result = 31 * result + Long.hashCode(fluids);
        result = 31 * result + Arrays.hashCode(magicAmounts);
        result = 31 * result + itemTypes;
        result = 31 * result + fluidTypes;
        result = 31 * result + itemCapacity;
        return 31 * result + fluidCapacity;
    }

    public int magicAmount(MagicResourceKind kind) { return magicAmounts[kind.ordinal()]; }

    public int goetySouls() { return magicAmount(MagicResourceKind.SOUL); }

    /** 是否存在需要展示的魔法资源余额。 */
    public boolean hasMagic() {
        for (int amount : magicAmounts) if (amount > 0) return true;
        return false;
    }

    public static UnifiedDiskSummary from(UnifiedDiskCore core) {
        core.checkThread();
        return new UnifiedDiskSummary(core.items.storedTotal(), core.fluids.storedTotal(), core.magicSnapshot(),
                core.items.occupiedSize(), core.fluids.occupiedSize(), core.limits.items(), core.limits.fluids());
    }
}
