package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.task.v6.CraftingTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CraftingTask.class, remap = false)
public interface CraftingTaskAccessor {
    @Accessor("network")
    INetwork rsi$getNetwork();
}
