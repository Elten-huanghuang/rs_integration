package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.refinedmods.refinedstorage.apiimpl.autocrafting.craftingmonitor.FluidCraftingMonitorElement;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FluidCraftingMonitorElement.class, remap = false)
public interface MonitorFluidAccessor {
    @Accessor("stack") FluidStack rsi$stack();
}
