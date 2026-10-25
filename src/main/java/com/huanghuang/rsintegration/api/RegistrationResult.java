package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;

public record RegistrationResult(
        RegistrationStatus status,
        @Nullable ResourceLocation registeredId,
        RegistrationPhase phase,
        List<IntegrationDiagnostic> diagnostics
) {
    public RegistrationResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        if (phase == null) throw new IllegalArgumentException("phase is required");
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (status == RegistrationStatus.ACCEPTED && registeredId == null) {
            throw new IllegalArgumentException(
                    "accepted registration requires registeredId");
        }
    }

    public boolean accepted() {
        return status == RegistrationStatus.ACCEPTED;
    }
}
