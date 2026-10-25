package com.huanghuang.rsintegration.api.machine;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import javax.annotation.Nullable;
import java.util.List;

public record MachineDescriptorResult(
        @Nullable MachineDescriptor descriptor,
        List<IntegrationDiagnostic> diagnostics
) {
    public MachineDescriptorResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (descriptor == null && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected descriptor requires diagnostics");
        }
    }

    public static MachineDescriptorResult ready(MachineDescriptor descriptor) {
        return new MachineDescriptorResult(descriptor, List.of());
    }

    public static MachineDescriptorResult rejected(
            IntegrationDiagnostic... diagnostics) {
        return new MachineDescriptorResult(null, List.of(diagnostics));
    }
}
