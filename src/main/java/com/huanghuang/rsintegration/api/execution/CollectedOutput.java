package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import net.minecraft.resources.ResourceLocation;

public record CollectedOutput(
        IntegrationResourceKey resource,
        long amount,
        OutputKind outputKind,
        ResourceLocation portId
) {
    public CollectedOutput {
        if (resource == null || amount <= 0L
                || outputKind == null || portId == null) {
            throw new IllegalArgumentException("collected output is incomplete");
        }
    }

    public static CollectedOutput primary(
            IntegrationResourceKey resource,
            long amount,
            ResourceLocation portId
    ) {
        return new CollectedOutput(
                resource, amount, OutputKind.PRIMARY, portId);
    }

    public static CollectedOutput secondary(
            IntegrationResourceKey resource,
            long amount,
            ResourceLocation portId
    ) {
        return new CollectedOutput(
                resource, amount, OutputKind.SECONDARY, portId);
    }
}
