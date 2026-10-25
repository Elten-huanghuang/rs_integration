package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationNames;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public record FluidRequirement(
        ResourceLocation entryId,
        FluidIngredientView fluid,
        long amount,
        InputConsumption consumption,
        String inputGroup,
        @Nullable Integer inputSlot,
        boolean recursive
) {
    public FluidRequirement {
        if (entryId == null || fluid == null) {
            throw new IllegalArgumentException("entryId and fluid are required");
        }
        if (amount <= 0L) throw new IllegalArgumentException("amount must be positive");
        if (consumption == null) throw new IllegalArgumentException("consumption is required");
        inputGroup = IntegrationNames.requireGroup(inputGroup, "inputGroup");
        if (inputSlot != null && inputSlot < 0) {
            throw new IllegalArgumentException("inputSlot must be non-negative");
        }
    }
}
