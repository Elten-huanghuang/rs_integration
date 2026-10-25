package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.recipe.RecipeView;

import javax.annotation.Nullable;
import java.util.List;

public record ExecutionClaim(
        @Nullable RecipeExecutionIntegration winner,
        List<RecipeExecutionIntegration> candidates,
        List<IntegrationDiagnostic> diagnostics
) {
    public ExecutionClaim {
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
