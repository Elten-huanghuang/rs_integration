package com.huanghuang.rsintegration.crafting;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Objects;

/** 任务账户内部使用的类型化流体身份，数量不包含在键中。 */
record TaskFluidKey(ResourceLocation fluidId, CompoundTag tag) {
    TaskFluidKey {
        Objects.requireNonNull(fluidId, "fluidId");
        tag = tag == null ? new CompoundTag() : tag.copy();
    }

    @Override
    public CompoundTag tag() {
        return tag.copy();
    }

    static TaskFluidKey of(FluidStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("流体不能为空");
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        if (id == null) throw new IllegalArgumentException("流体未注册");
        return new TaskFluidKey(id, stack.getTag());
    }

    FluidStack toStack(int amount) {
        if (amount <= 0) return FluidStack.EMPTY;
        var fluid = ForgeRegistries.FLUIDS.getValue(fluidId);
        if (fluid == null) return FluidStack.EMPTY;
        FluidStack stack = new FluidStack(fluid, amount);
        if (!tag.isEmpty()) stack.setTag(tag.copy());
        return stack;
    }
}
