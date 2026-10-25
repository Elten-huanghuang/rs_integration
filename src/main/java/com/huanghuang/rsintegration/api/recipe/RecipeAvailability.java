package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import java.util.List;

public record RecipeAvailability(
        boolean planningAvailable,
        boolean executionAvailable,
        List<IntegrationDiagnostic> diagnostics
) {
    public RecipeAvailability {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (executionAvailable && !planningAvailable) {
            throw new IllegalArgumentException(
                    "execution availability requires planning availability");
        }
        if (!planningAvailable && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "unavailable recipe requires diagnostics");
        }
    }

    public static RecipeAvailability available() {
        return new RecipeAvailability(true, true, List.of());
    }
}
