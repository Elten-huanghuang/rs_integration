package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.execution.FailureReason;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public record ConsumerOrderStatus(
        ConsumerOrderId orderId,
        ResourceLocation consumerId,
        ResourceLocation recipeId,
        ConsumerOrderState state,
        long completedOperations,
        long totalOperations,
        long runningOperations,
        List<ConsumerOutputResult> outputs,
        @Nullable FailureReason failureReason,
        List<IntegrationDiagnostic> diagnostics
) {
    public ConsumerOrderStatus {
        if (orderId == null || consumerId == null || recipeId == null
                || state == null || completedOperations < 0L
                || totalOperations < 0L || runningOperations < 0L
                || completedOperations > totalOperations) {
            throw new IllegalArgumentException(
                    "order status identity is incomplete");
        }
        if (outputs != null
                && outputs.stream().anyMatch(Objects::isNull)
                || diagnostics != null
                && diagnostics.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "order status details must not contain null");
        }
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean terminal() {
        return state == ConsumerOrderState.SUCCEEDED
                || state == ConsumerOrderState.FAILED
                || state == ConsumerOrderState.CANCELLED;
    }
}
