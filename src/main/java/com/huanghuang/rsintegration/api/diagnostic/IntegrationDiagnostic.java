package com.huanghuang.rsintegration.api.diagnostic;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;

public record IntegrationDiagnostic(
        IntegrationDiagnosticCode code,
        IntegrationSeverity severity,
        @Nullable ResourceLocation sourceId,
        Map<String, String> attributes
) {
    public IntegrationDiagnostic {
        if (code == null || severity == null) {
            throw new IllegalArgumentException("code and severity are required");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static IntegrationDiagnostic info(IntegrationDiagnosticCode code) {
        return new IntegrationDiagnostic(code, IntegrationSeverity.INFO, null, Map.of());
    }

    public static IntegrationDiagnostic warning(IntegrationDiagnosticCode code) {
        return new IntegrationDiagnostic(code, IntegrationSeverity.WARNING, null, Map.of());
    }

    public static IntegrationDiagnostic error(IntegrationDiagnosticCode code) {
        return new IntegrationDiagnostic(code, IntegrationSeverity.ERROR, null, Map.of());
    }
}
