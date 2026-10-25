package com.huanghuang.rsintegration.api.machine;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import javax.annotation.Nullable;
import java.util.List;

public record MachineClaim(
        @Nullable MachineIntegration winner,
        List<MachineIntegration> candidates,
        List<IntegrationDiagnostic> diagnostics
) {
    public MachineClaim {
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
