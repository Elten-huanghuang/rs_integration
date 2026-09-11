package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.ModItems;
import com.refinedmods.refinedstorage.inventory.item.FilterItemHandler;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FilterItemHandler.class, remap = false)
public abstract class FilterItemHandlerVoidUpgradeMixin {
    @Inject(method = "handleFilterItem", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$doNotApplyVoidUpgradeAsDisplayFilter(ItemStack stack, CallbackInfo ci) {
        if (ModItems.RS_VOID_UPGRADE != null && stack.is(ModItems.RS_VOID_UPGRADE.get())) {
            ci.cancel();
        }
    }
}
