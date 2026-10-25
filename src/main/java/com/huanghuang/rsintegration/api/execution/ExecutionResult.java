package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import javax.annotation.Nullable;
import java.util.List;

public record ExecutionResult(
        ExecutionStartStatus status,
        @Nullable RunningOperation operation,
        int retryAfterTicks,
        List<IntegrationDiagnostic> diagnostics
) {
    public ExecutionResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        if (retryAfterTicks < 0) {
            throw new IllegalArgumentException("retryAfterTicks must be non-negative");
        }
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (status == ExecutionStartStatus.STARTED && operation == null) {
            throw new IllegalArgumentException("STARTED requires an operation");
        }
        if (status != ExecutionStartStatus.RETRY && retryAfterTicks != 0) {
            throw new IllegalArgumentException(
                    "retryAfterTicks is only valid for RETRY");
        }
        if (status == ExecutionStartStatus.REJECTED && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "REJECTED requires diagnostics");
        }
    }

    public static ExecutionResult started(RunningOperation operation) {
        return new ExecutionResult(
                ExecutionStartStatus.STARTED, operation, 0, List.of());
    }

    public static ExecutionResult retry(
            int retryAfterTicks,
            IntegrationDiagnostic... diagnostics
    ) {
        return new ExecutionResult(
                ExecutionStartStatus.RETRY, null, retryAfterTicks,
                List.of(diagnostics));
    }

    public static ExecutionResult rejected(
            IntegrationDiagnostic... diagnostics
    ) {
        return new ExecutionResult(
                ExecutionStartStatus.REJECTED, null, 0, List.of(diagnostics));
    }
}
