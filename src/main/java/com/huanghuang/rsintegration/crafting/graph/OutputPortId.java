package com.huanghuang.rsintegration.crafting.graph;

public record OutputPortId(NodeId nodeId, int index) {
    public OutputPortId {
        if (nodeId == null) throw new NullPointerException("nodeId");
        if (index < 0) throw new IllegalArgumentException("output index must be non-negative");
    }
}
