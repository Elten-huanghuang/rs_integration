package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;

import java.util.LinkedHashSet;
import java.util.List;

public record FluidIngredientView(
        List<IntegrationResourceKey> alternatives,
        ResourceMatchMode matchMode
) {
    public FluidIngredientView {
        if (alternatives == null || alternatives.isEmpty()) {
            throw new IllegalArgumentException("at least one fluid alternative is required");
        }
        alternatives = List.copyOf(new LinkedHashSet<>(alternatives));
        if (alternatives.stream().anyMatch(resource ->
                resource.kind() != IntegrationResourceKey.ResourceKind.FLUID)) {
            throw new IllegalArgumentException("fluid alternatives must use FLUID kind");
        }
        if (matchMode == null) {
            throw new IllegalArgumentException("matchMode is required");
        }
        if (matchMode == ResourceMatchMode.PARTIAL_IDENTITY
                && alternatives.stream().anyMatch(resource ->
                resource.identity().isEmpty())) {
            throw new IllegalArgumentException(
                    "partial fluid identity requires a non-empty template");
        }
    }
}
