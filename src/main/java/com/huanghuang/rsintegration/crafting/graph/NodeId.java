package com.huanghuang.rsintegration.crafting.graph;

public record NodeId(int value) implements Comparable<NodeId> {
    public NodeId {
        if (value < 0) throw new IllegalArgumentException("node id must be non-negative");
    }

    @Override
    public int compareTo(NodeId other) {
        return Integer.compare(value, other.value);
    }
}
