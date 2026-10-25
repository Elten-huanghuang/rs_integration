package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationNames;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public record MaterialRequirement(
        ResourceLocation entryId,
        ItemIngredientView ingredient,
        long amount,
        InputConsumption consumption,
        String inputGroup,
        @Nullable Integer inputSlot,
        boolean recursive
) {
    public MaterialRequirement {
        if (entryId == null) throw new IllegalArgumentException("entryId is required");
        if (ingredient == null) throw new IllegalArgumentException("ingredient is required");
        if (amount <= 0L) throw new IllegalArgumentException("amount must be positive");
        if (consumption == null) throw new IllegalArgumentException("consumption is required");
        inputGroup = IntegrationNames.requireGroup(inputGroup, "inputGroup");
        if (inputSlot != null && inputSlot < 0) {
            throw new IllegalArgumentException("inputSlot must be non-negative");
        }
    }
}
