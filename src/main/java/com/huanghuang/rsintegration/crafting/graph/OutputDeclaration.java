package com.huanghuang.rsintegration.crafting.graph;

import java.util.Objects;

public record OutputDeclaration(OutputPortId id, MaterialKey material, int quantity, OutputKind kind) {
    public OutputDeclaration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(kind, "kind");
        if (quantity <= 0) throw new IllegalArgumentException("output quantity must be positive");
    }
}
