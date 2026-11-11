package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.refinedmods.refinedstorage.apiimpl.autocrafting.craftingmonitor.ItemCraftingMonitorElement;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.craftingmonitor.FluidCraftingMonitorElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = {ItemCraftingMonitorElement.class, FluidCraftingMonitorElement.class}, remap = false)
public interface MonitorAmountsAccessor {
    @Accessor("stored") int rsi$stored();
    @Accessor("missing") int rsi$missing();
    @Accessor("processing") int rsi$processing();
    @Accessor("scheduled") int rsi$scheduled();
    @Accessor("crafting") int rsi$crafting();
}
