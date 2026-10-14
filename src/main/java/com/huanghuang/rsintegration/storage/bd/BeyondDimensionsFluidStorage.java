package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.crafting.fluid.FluidContainerTransfer;
import com.wintercogs.beyonddimensions.api.storage.handler.IStackHandler;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.FluidStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** BD 终端资源适配；数量按 mB 传递，流体身份保留完整 NBT。 */
public final class BeyondDimensionsFluidStorage implements FluidContainerTransfer.Storage {
    private final IStackHandler storage;

    public BeyondDimensionsFluidStorage(IStackHandler storage) { this.storage = storage; }

    @Override public ItemStack extractItem(ItemStack stack, int amount, boolean simulate) {
        KeyAmount extracted = storage.extract(new ItemStackKey(stack), amount, simulate, false);
        return extracted.isEmpty() ? ItemStack.EMPTY : (ItemStack) extracted.toStack();
    }

    @Override public ItemStack insertItem(ItemStack stack, int amount, boolean simulate) {
        KeyAmount remainder = storage.insert(new ItemStackKey(stack), amount, simulate);
        return remainder.isEmpty() ? ItemStack.EMPTY : (ItemStack) remainder.toStack();
    }

    @Override public FluidStack extractFluid(FluidStack fluid, int amount, boolean simulate) {
        KeyAmount extracted = storage.extract(new FluidStackKey(fluid), amount, simulate, false);
        return extracted.isEmpty() ? FluidStack.EMPTY : (FluidStack) extracted.toStack();
    }

    @Override public FluidStack insertFluid(FluidStack fluid, int amount, boolean simulate) {
        KeyAmount remainder = storage.insert(new FluidStackKey(fluid), amount, simulate);
        return remainder.isEmpty() ? FluidStack.EMPTY : (FluidStack) remainder.toStack();
    }
}
