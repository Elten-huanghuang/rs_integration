package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;

public record CraftObservation(
        LifecyclePhase phase,
        int completedTicks,
        int totalTicks,
        long stateRevision,
        @Nullable ResourceLocation activeRecipeId,
        List<IntegrationDiagnostic> diagnostics
) {
    public CraftObservation {
        if (phase == null) throw new IllegalArgumentException("phase is required");
        if (completedTicks < 0 || totalTicks < 0
                || totalTicks > 0 && completedTicks > totalTicks
                || stateRevision < 0L) {
            throw new IllegalArgumentException("invalid observation values");
        }
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
