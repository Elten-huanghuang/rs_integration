package com.huanghuang.rsintegration.magic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 一次配方执行消耗的一种魔法资源费用。 */
public record MagicResourceCost(MagicResourceKind kind, int amountPerOperation) {
    public MagicResourceCost {
        Objects.requireNonNull(kind, "kind");
        if (amountPerOperation <= 0) {
            throw new IllegalArgumentException("magic resource cost must be positive");
        }
    }

    public long totalFor(int operations) {
        return saturatingMultiply(amountPerOperation, Math.max(0, operations));
    }

    /** 合并同种资源费用，保持枚举顺序稳定。 */
    public static List<MagicResourceCost> merge(List<MagicResourceCost> costs) {
        if (costs == null || costs.isEmpty()) return List.of();
        Map<MagicResourceKind, Long> totals = new EnumMap<>(MagicResourceKind.class);
        for (MagicResourceCost cost : costs) {
            if (cost == null) continue;
            totals.merge(cost.kind(), (long) cost.amountPerOperation(), MagicResourceCost::saturatingAdd);
        }
        List<MagicResourceCost> result = new ArrayList<>(totals.size());
        for (MagicResourceKind kind : MagicResourceKind.values()) {
            long total = totals.getOrDefault(kind, 0L);
            if (total > 0L) result.add(new MagicResourceCost(
                    kind, (int) Math.min(Integer.MAX_VALUE, total)));
        }
        return List.copyOf(result);
    }

    private static long saturatingMultiply(int left, int right) {
        return Math.min(Long.MAX_VALUE, (long) left * right);
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
