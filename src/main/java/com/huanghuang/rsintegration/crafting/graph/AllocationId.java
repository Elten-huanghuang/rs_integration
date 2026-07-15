package com.huanghuang.rsintegration.crafting.graph;

public record AllocationId(long value) {
    public AllocationId {
        if (value < 0) throw new IllegalArgumentException("allocation id must be non-negative");
    }
}
