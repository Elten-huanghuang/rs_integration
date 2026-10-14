package com.huanghuang.rsintegration.mixin.beyonddimensions;

import com.huanghuang.rsintegration.storage.bd.BeyondDimensionsFluidInteraction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.common.menu.BDBaseMenu", remap = false)
public abstract class BDFluidContainerMixin {
    @Inject(method = "customClickHandler", at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$fluidContainers(int slot, @Coerce Object selected, int button, boolean shift, CallbackInfo ci) {
        if (BeyondDimensionsFluidInteraction.handle((AbstractContainerMenu) (Object) this,
                slot, selected, button, shift)) ci.cancel();
    }
}
