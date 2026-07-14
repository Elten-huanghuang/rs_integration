package com.huanghuang.rsintegration.crafting.graph;

import java.util.Objects;

public record MaterialAllocation(
        AllocationId id,
        InputPortId consumer,
        MaterialSource source,
        MaterialKey material,
        int quantity
) {
    public MaterialAllocation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(material, "material");
        if (quantity <= 0) throw new IllegalArgumentException("allocation quantity must be positive");
    }
}
