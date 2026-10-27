package com.huanghuang.rsintegration.api.resonance;

import net.minecraft.world.item.ItemStack;

public record ResonanceInsertion(
        ItemStack remainder,
        long resultingRevision
) {
    public ResonanceInsertion {
        if (remainder == null) {
            throw new IllegalArgumentException(
                    "insertion remainder is required");
        }
        if (resultingRevision < 0L) {
            throw new IllegalArgumentException(
                    "resulting revision must be non-negative");
        }
        remainder = remainder.copy();
    }

    @Override
    public ItemStack remainder() {
        return remainder.copy();
    }
}
