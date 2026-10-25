package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.IntegrationNames;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public record OutputDeclaration(
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
    public OutputDeclaration {
        if (portId == null || outputGroup == null || outputGroup.isBlank()
                || resource == null
                || amount == null || outputKind == null
                || probability == null) {
            throw new IllegalArgumentException("output declaration is incomplete");
        }
        outputGroup = IntegrationNames.requireGroup(
                outputGroup, "outputGroup");
        if (dynamicIdentity && resource.identity().isEmpty()) {
            throw new IllegalArgumentException(
                    "dynamic identity requires a non-empty resource template");
        }
        if ((outputKind == OutputKind.TRANSFORMED
                || outputKind == OutputKind.CONTAINER
                || outputKind == OutputKind.CATALYST_RETURN
                || outputKind == OutputKind.DURABILITY_RETURN)
                && sourceInputEntryId == null) {
            throw new IllegalArgumentException(
                    "returned/transformed output requires a source input entry");
        }
    }
}
