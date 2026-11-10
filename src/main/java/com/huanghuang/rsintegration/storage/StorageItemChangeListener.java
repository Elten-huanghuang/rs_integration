package com.huanghuang.rsintegration.storage;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** Receives authoritative absolute item counts from a live storage backend. */
public interface StorageItemChangeListener {
    void onChanged(ItemStack stack, long amount);

    /** 流体的绝对数量变化；旧订阅者仍可选择整体失效，新订阅者按身份更新。 */
    default void onFluidChanged(FluidStack stack, long amount) { onInvalidated(); }

    /** The native cache/storage handle was rebuilt and must be rebound. */
    default void onInvalidated() {}
}
