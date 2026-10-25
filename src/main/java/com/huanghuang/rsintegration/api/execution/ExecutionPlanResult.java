package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import javax.annotation.Nullable;
import java.util.List;

public record ExecutionPlanResult(
        @Nullable ExecutionPlan plan,
        List<IntegrationDiagnostic> diagnostics
) {
    public ExecutionPlanResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (plan == null && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected execution plan requires diagnostics");
        }
    }

    public static ExecutionPlanResult ready(ExecutionPlan plan) {
        return new ExecutionPlanResult(plan, List.of());
    }

    public static ExecutionPlanResult rejected(
            IntegrationDiagnostic... diagnostics) {
        return new ExecutionPlanResult(null, List.of(diagnostics));
    }
}
