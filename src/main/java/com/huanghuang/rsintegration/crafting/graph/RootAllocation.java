package com.huanghuang.rsintegration.crafting.graph;

import java.util.Objects;

public record RootAllocation(MaterialSource source, MaterialKey material, int quantity) {
    public RootAllocation {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(material, "material");
        if (quantity <= 0) throw new IllegalArgumentException("root allocation quantity must be positive");
    }
}
