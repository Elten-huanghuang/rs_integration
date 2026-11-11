package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.refinedmods.refinedstorage.apiimpl.autocrafting.craftingmonitor.ItemCraftingMonitorElement;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ItemCraftingMonitorElement.class, remap = false)
public interface MonitorItemAccessor {
    @Accessor("stack") ItemStack rsi$stack();
}
