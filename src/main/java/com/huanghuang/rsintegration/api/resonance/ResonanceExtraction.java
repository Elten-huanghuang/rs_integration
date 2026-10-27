package com.huanghuang.rsintegration.api.resonance;

import net.minecraft.world.item.ItemStack;

public record ResonanceExtraction(
        ItemStack stack,
        long resultingRevision
) {
    public ResonanceExtraction {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException(
                    "extracted stack must not be empty");
        }
        if (resultingRevision < 0L) {
            throw new IllegalArgumentException(
                    "resulting revision must be non-negative");
        }
        stack = stack.copy();
    }

    @Override
    public ItemStack stack() {
        return stack.copy();
    }
}
