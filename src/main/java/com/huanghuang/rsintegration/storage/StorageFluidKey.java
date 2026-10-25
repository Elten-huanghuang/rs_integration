package com.huanghuang.rsintegration.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Objects;

/** 后端无关的流体身份；数量不进入 key。 */
public record StorageFluidKey(
        StorageBackendId backendId,
        ResourceLocation fluidId,
        CompoundTag tag
) {
    public StorageFluidKey {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(fluidId, "fluidId");
        tag = tag == null ? new CompoundTag() : tag.copy();
    }

    @Override
    public CompoundTag tag() {
        return tag.copy();
    }

    public static StorageFluidKey fromStack(
            StorageBackendId backendId,
            FluidStack stack
    ) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("fluid stack must not be empty");
        }
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        if (id == null) {
            throw new IllegalArgumentException("unregistered fluid");
        }
        return new StorageFluidKey(
                backendId,
                id,
                stack.getTag() == null ? new CompoundTag() : stack.getTag()
        );
    }

    public FluidStack toStack(long amount) {
        var fluid = ForgeRegistries.FLUIDS.getValue(fluidId);
        if (fluid == null || amount <= 0L) return FluidStack.EMPTY;
        FluidStack result = new FluidStack(
                fluid, (int) Math.min(Integer.MAX_VALUE, amount));
        if (!tag.isEmpty()) result.setTag(tag.copy());
        return result;
    }
}
