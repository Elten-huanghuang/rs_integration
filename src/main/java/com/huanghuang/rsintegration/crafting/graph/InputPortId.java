package com.huanghuang.rsintegration.crafting.graph;

public record InputPortId(NodeId nodeId, int index) {
    public InputPortId {
        if (nodeId == null) throw new NullPointerException("nodeId");
        if (index < 0) throw new IllegalArgumentException("input index must be non-negative");
    }
}
