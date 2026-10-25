package com.huanghuang.rsintegration.storage;

import net.minecraftforge.fluids.FluidStack;

import java.util.Objects;

public record StoredFluid(StorageFluidKey key, long amount) {
    public StoredFluid {
        Objects.requireNonNull(key, "key");
        if (amount <= 0L) {
            throw new IllegalArgumentException("fluid amount must be positive");
        }
    }

    public FluidStack stack() {
        FluidStack stack = key.toStack(1);
        return stack.isEmpty() ? FluidStack.EMPTY : stack;
    }
}
