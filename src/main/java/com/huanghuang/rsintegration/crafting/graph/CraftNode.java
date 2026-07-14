package com.huanghuang.rsintegration.crafting.graph;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public record CraftNode(
        NodeId id,
        ResourceLocation recipeId,
        String modTypeId,
        @Nullable ResourceLocation recipeTypeId,
        int executions,
        List<ResourceLocation> alternativeIds,
        List<String> alternativeModTypeIds,
        boolean inferMode,
        @Nullable ItemStack syntheticInput,
        @Nullable ItemStack syntheticOutput,
        List<InputDemand> inputs,
        List<OutputDeclaration> outputs
) {
    public CraftNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(modTypeId, "modTypeId");
        if (executions <= 0) throw new IllegalArgumentException("executions must be positive");
        alternativeIds = List.copyOf(alternativeIds);
        alternativeModTypeIds = List.copyOf(alternativeModTypeIds);
        syntheticInput = copyUnit(syntheticInput);
        syntheticOutput = copyUnit(syntheticOutput);
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    private static ItemStack copyUnit(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.copyWithCount(1);
    }
}
