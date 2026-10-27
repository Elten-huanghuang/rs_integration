package com.huanghuang.rsintegration.api.resonance;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public record ResonanceResult<T>(
        @Nullable T value,
        List<ResonanceDiagnostic> diagnostics
) {
    public ResonanceResult {
        diagnostics = diagnostics == null
                ? List.of() : List.copyOf(diagnostics);
        if (diagnostics.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "resonance diagnostics must not contain null");
        }
        if (value == null && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "a failed resonance request requires diagnostics");
        }
    }

    public boolean successful() {
        return value != null;
    }

    public static <T> ResonanceResult<T> success(T value) {
        return new ResonanceResult<>(
                Objects.requireNonNull(value, "value"), List.of());
    }

    public static <T> ResonanceResult<T> rejected(
            ResonanceDiagnostic... diagnostics
    ) {
        return new ResonanceResult<>(null, List.of(diagnostics));
    }
}
