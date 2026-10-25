package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import javax.annotation.Nullable;
import java.util.List;

public record RecipeInspectionResult(
        @Nullable RecipeView view,
        List<IntegrationDiagnostic> diagnostics
) {
    public RecipeInspectionResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (view == null && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected inspection requires diagnostics");
        }
    }

    public static RecipeInspectionResult rejected(
            IntegrationDiagnostic... diagnostics) {
        return new RecipeInspectionResult(null, List.of(diagnostics));
    }
}
