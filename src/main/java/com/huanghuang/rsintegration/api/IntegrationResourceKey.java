package com.huanghuang.rsintegration.api;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

public record IntegrationResourceKey(
        ResourceKind kind,
        ResourceLocation id,
        CompoundTag identity
) {
    public enum ResourceKind {
        ITEM,
        FLUID
    }

    public IntegrationResourceKey {
        if (kind == null || id == null) {
            throw new IllegalArgumentException("kind and id are required");
        }
        identity = identity == null ? new CompoundTag() : identity.copy();
    }

    @Override
    public CompoundTag identity() {
        return identity.copy();
    }

    public static IntegrationResourceKey item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("item stack must not be empty");
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        CompoundTag tag = stack.getTag();
        return new IntegrationResourceKey(
                ResourceKind.ITEM,
                itemId,
                tag == null ? new CompoundTag() : tag
        );
    }

    public static IntegrationResourceKey fluid(FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("fluid stack must not be empty");
        }
        ResourceLocation fluidId = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        CompoundTag tag = stack.getTag();
        return new IntegrationResourceKey(
                ResourceKind.FLUID,
                fluidId,
                tag == null ? new CompoundTag() : tag
        );
    }
}
