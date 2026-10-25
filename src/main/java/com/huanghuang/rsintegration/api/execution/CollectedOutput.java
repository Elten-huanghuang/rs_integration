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
}
