package com.huanghuang.rsintegration.crafting.graph;

import javax.annotation.Nullable;
import java.util.Objects;

public record OutputDeclaration(
        OutputPortId id,
        MaterialKey material,
        long quantity,
        OutputKind kind,
        long minimumQuantity,
        long maximumQuantity,
        OutputProbability probability,
        boolean dynamicIdentity,
        @Nullable InputPortId sourceInput
) {
    public OutputDeclaration(OutputPortId id, MaterialKey material, long quantity, OutputKind kind) {
        this(id, material, quantity, kind, quantity, quantity,
                OutputProbability.guaranteed(), false, null);
    }

    public OutputDeclaration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(probability, "probability");
        if (quantity < 0L) throw new IllegalArgumentException("output quantity must be non-negative");
        if (minimumQuantity < 0L || maximumQuantity < minimumQuantity || maximumQuantity == 0L) {
            throw new IllegalArgumentException("invalid output quantity range");
        }
        if (quantity < minimumQuantity || quantity > maximumQuantity) {
            throw new IllegalArgumentException("guaranteed output quantity must be in its range");
        }
        if ((kind == OutputKind.CONTAINER
                || kind == OutputKind.TRANSFORMED
                || kind == OutputKind.CATALYST_RETURN
                || kind == OutputKind.DURABILITY_RETURN)
                && sourceInput == null) {
            throw new IllegalArgumentException(kind + " requires a source input");
        }
    }
}
