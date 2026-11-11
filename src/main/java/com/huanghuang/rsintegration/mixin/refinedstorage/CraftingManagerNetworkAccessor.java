package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.CraftingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CraftingManager.class, remap = false)
public interface CraftingManagerNetworkAccessor {
    @Accessor("network")
    INetwork rsi$getNetwork();
}
