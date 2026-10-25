package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;

public record ConsumerDiagnostic(
        ConsumerDiagnosticCode code,
        IntegrationSeverity severity,
        String detail,
        boolean retryable
) {
    public ConsumerDiagnostic {
        if (code == null || severity == null || detail == null) {
            throw new IllegalArgumentException("diagnostic is incomplete");
        }
    }

    public static ConsumerDiagnostic error(
            ConsumerDiagnosticCode code,
            String detail,
            boolean retryable
    ) {
        return new ConsumerDiagnostic(
                code, IntegrationSeverity.ERROR, detail, retryable);
    }
}
