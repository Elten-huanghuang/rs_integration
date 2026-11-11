package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.refinedmods.refinedstorage.api.autocrafting.craftingmonitor.ICraftingMonitorElement;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.craftingmonitor.ErrorCraftingMonitorElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ErrorCraftingMonitorElement.class, remap = false)
public interface MonitorErrorAccessor {
    @Accessor("base") ICraftingMonitorElement rsi$base();
}
