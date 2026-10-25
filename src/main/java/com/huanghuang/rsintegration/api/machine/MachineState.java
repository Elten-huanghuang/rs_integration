package com.huanghuang.rsintegration.api.machine;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public record MachineState(
        boolean loaded,
        boolean valid,
        boolean busy,
        int progress,
        int totalProgress,
        @Nullable ResourceLocation activeRecipeId,
        @Nullable ResourceLocation activeProcessId,
        long stateRevision,
        @Nullable IntegrationDiagnostic failure
) {
    public MachineState {
        if (progress < 0 || totalProgress < 0
                || progress > totalProgress && totalProgress > 0) {
            throw new IllegalArgumentException("invalid machine progress");
        }
        if (stateRevision < 0L) {
            throw new IllegalArgumentException("stateRevision must be non-negative");
        }
        if (busy && activeRecipeId == null) {
            throw new IllegalArgumentException(
                    "busy machine must report activeRecipeId");
        }
        if ((!loaded || !valid) && busy) {
            throw new IllegalArgumentException(
                    "unloaded or invalid machine cannot be busy");
        }
    }

    public static MachineState invalid() {
        return new MachineState(false, false, false, 0, 0,
                null, null, 0L, null);
    }

    public static MachineState invalid(IntegrationDiagnostic failure) {
        return new MachineState(false, false, false, 0, 0,
                null, null, 0L, failure);
    }
}
