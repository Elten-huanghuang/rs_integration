package com.huanghuang.rsintegration.api.resonance;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;

public record ResonanceDiagnostic(
        ResonanceDiagnosticCode code,
        IntegrationSeverity severity,
        String detail,
        boolean retryable
) {
    public ResonanceDiagnostic {
        if (code == null || severity == null
                || detail == null || detail.isBlank()) {
            throw new IllegalArgumentException(
                    "resonance diagnostic is incomplete");
        }
    }

    public static ResonanceDiagnostic error(
            ResonanceDiagnosticCode code,
            String detail,
            boolean retryable
    ) {
        return new ResonanceDiagnostic(
                code,
                IntegrationSeverity.ERROR,
                detail,
                retryable
        );
    }
}
