package com.huanghuang.rsintegration.crafting.loadbalancer;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** 操作编号的半开区间集合；空间随区间数变化，不为每次操作分配标记。 */
final class OperationRangeSet {
    record Range(int first, int end) {
        int count() { return end - first; }
    }

    private final TreeMap<Integer, Integer> ranges = new TreeMap<>();

    OperationRangeSet(int total) {
        if (total <= 0) throw new IllegalArgumentException("total must be positive");
        ranges.put(0, total);
    }

    boolean contains(int operation) {
        var entry = ranges.floorEntry(operation);
        return entry != null && operation < entry.getValue();
    }

    List<Range> ranges() {
        return ranges.entrySet().stream().map(entry -> new Range(entry.getKey(), entry.getValue())).toList();
    }

    /** 移除并返回原集合内实际存在的部分，重复结算不会再扣料。 */
    List<Range> remove(int first, int end) {
        if (first >= end) return List.of();
        List<Range> removed = new ArrayList<>();
        var entry = ranges.floorEntry(first);
        if (entry == null || entry.getValue() <= first) entry = ranges.ceilingEntry(first);
        while (entry != null && entry.getKey() < end) {
            int start = entry.getKey();
            int stop = entry.getValue();
            ranges.remove(start);
            if (start < first) ranges.put(start, first);
            if (stop > end) ranges.put(end, stop);
            removed.add(new Range(Math.max(first, start), Math.min(end, stop)));
            entry = ranges.ceilingEntry(end > stop ? stop : end);
        }
        return removed;
    }

    void add(int first, int end) {
        if (first >= end) return;
        var before = ranges.floorEntry(first);
        if (before != null && before.getValue() >= first) {
            first = before.getKey();
            end = Math.max(end, before.getValue());
            ranges.remove(first);
        }
        var next = ranges.ceilingEntry(first);
        while (next != null && next.getKey() <= end) {
            end = Math.max(end, next.getValue());
            ranges.remove(next.getKey());
            next = ranges.ceilingEntry(first);
        }
        ranges.put(first, end);
    }
}
