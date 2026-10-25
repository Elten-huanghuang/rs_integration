package com.huanghuang.rsintegration.crafting.graph;

import java.util.Objects;
import java.util.UUID;

/** 一次物理启动的稳定身份。 */
public record OperationId(UUID craftId, NodeId nodeId, int sequence) {
    public OperationId {
        Objects.requireNonNull(craftId, "craftId");
        Objects.requireNonNull(nodeId, "nodeId");
        if (sequence < 0) {
            throw new IllegalArgumentException("operation sequence must be non-negative");
        }
    }
}
