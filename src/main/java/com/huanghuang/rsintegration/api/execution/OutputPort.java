package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.IntegrationNames;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.recipe.OutputAmount;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.OutputProbability;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public record OutputPort(
        ResourceLocation portId,
        String outputGroup,
        IntegrationResourceKey resource,
        OutputAmount amount,
        OutputKind outputKind,
        OutputProbability probability,
        boolean dynamicIdentity,
        boolean targetProducing,
        @Nullable ResourceLocation sourceInputEntryId
) {
    public OutputPort {
        if (portId == null || outputGroup == null || outputGroup.isBlank()
                || resource == null
                || amount == null || outputKind == null || probability == null) {
            throw new IllegalArgumentException("output port is incomplete");
        }
        outputGroup = IntegrationNames.requireGroup(outputGroup, "outputGroup");
        if (dynamicIdentity && resource.identity().isEmpty()) {
            throw new IllegalArgumentException(
                    "dynamic output requires a non-empty identity template");
        }
    }
}
