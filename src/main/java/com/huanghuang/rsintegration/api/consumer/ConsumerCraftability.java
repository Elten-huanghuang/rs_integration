package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public record ConsumerCraftability(
        ConsumerRecipeKey recipe,
        ConsumerCraftabilityStatus status,
        long requestedQuantity,
        long executableQuantity,
        List<ConsumerResourceShortage> shortages,
        @Nullable ConsumerPlanId planId,
        List<IntegrationDiagnostic> diagnostics
) {
    public ConsumerCraftability {
        if (recipe == null || status == null
                || requestedQuantity < 0L || executableQuantity < 0L) {
            throw new IllegalArgumentException(
                    "craftability identity is incomplete");
        }
        if (shortages != null
                && shortages.stream().anyMatch(Objects::isNull)
                || diagnostics != null
                && diagnostics.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "craftability details must not contain null");
        }
        shortages = shortages == null ? List.of() : List.copyOf(shortages);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (status == ConsumerCraftabilityStatus.READY && planId == null) {
            throw new IllegalArgumentException("READY requires a plan id");
        }
        if (status != ConsumerCraftabilityStatus.READY && planId != null) {
            throw new IllegalArgumentException(
                    "non-READY result must not expose a plan");
        }
        if (status == ConsumerCraftabilityStatus.MISSING_MATERIALS
                && shortages.isEmpty()) {
            throw new IllegalArgumentException(
                    "missing materials requires a shortage");
        }
        if (status != ConsumerCraftabilityStatus.READY
                && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "unavailable result requires diagnostics");
        }
    }

    public boolean canSubmit() {
        return status == ConsumerCraftabilityStatus.READY;
    }
}
