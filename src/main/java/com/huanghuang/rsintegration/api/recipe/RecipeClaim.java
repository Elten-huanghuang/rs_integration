package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import javax.annotation.Nullable;
import java.util.List;

public record RecipeClaim(
        @Nullable RecipeIntegration<?> winner,
        List<RecipeIntegration<?>> candidates,
        List<IntegrationDiagnostic> diagnostics
) {
    public RecipeClaim {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (winner != null && !candidates.contains(winner)) {
            throw new IllegalArgumentException(
                    "winner must be one of the candidates");
        }
    }

    public boolean ambiguous() {
        return candidates.size() > 1;
    }
}
