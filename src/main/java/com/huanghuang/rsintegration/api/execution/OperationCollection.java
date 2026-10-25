package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import java.util.List;

public record OperationCollection(
        long collectionSequence,
        List<CollectedOutput> outputs,
        List<MaterialDisposition> materials,
        boolean complete,
        List<IntegrationDiagnostic> diagnostics
) {
    public OperationCollection {
        if (collectionSequence < 1L) {
            throw new IllegalArgumentException(
                    "collectionSequence must be positive");
        }
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        materials = materials == null ? List.of() : List.copyOf(materials);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
