package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import java.util.List;

public record OperationCleanupResult(
        boolean cleanupComplete,
        List<MaterialDisposition> materials,
        List<CollectedOutput> recoveredOutputs,
        List<IntegrationDiagnostic> diagnostics
) {
    public OperationCleanupResult {
        materials = materials == null ? List.of() : List.copyOf(materials);
        recoveredOutputs = recoveredOutputs == null
                ? List.of() : List.copyOf(recoveredOutputs);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (cleanupComplete && materials.stream().anyMatch(disposition ->
                disposition.outcome() == MaterialOutcome.UNCERTAIN)) {
            throw new IllegalArgumentException(
                    "cleanup cannot be complete with uncertain materials");
        }
    }
}
