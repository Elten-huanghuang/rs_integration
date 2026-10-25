package com.huanghuang.rsintegration.api.consumer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public record ConsumerResult<T>(
        @Nullable T value,
        List<ConsumerDiagnostic> diagnostics,
        boolean idempotentReplay
) {
    public ConsumerResult {
        if (diagnostics != null
                && diagnostics.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "consumer diagnostics must not contain null");
        }
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (value == null && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "a rejected consumer request requires diagnostics");
        }
    }

    public boolean successful() {
        return value != null;
    }

    public static <T> ConsumerResult<T> success(T value) {
        return new ConsumerResult<>(
                Objects.requireNonNull(value, "value"), List.of(), false);
    }

    public static <T> ConsumerResult<T> replay(T value) {
        return new ConsumerResult<>(
                Objects.requireNonNull(value, "value"), List.of(), true);
    }

    public static <T> ConsumerResult<T> rejected(
            ConsumerDiagnostic... diagnostics
    ) {
        return new ConsumerResult<>(null, List.of(diagnostics), false);
    }

    public static <T> ConsumerResult<T> rejected(
            List<ConsumerDiagnostic> diagnostics,
            boolean idempotentReplay
    ) {
        return new ConsumerResult<>(null, diagnostics, idempotentReplay);
    }
}
