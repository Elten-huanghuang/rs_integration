package com.huanghuang.rsintegration.mixin.goety;

import com.huanghuang.rsintegration.mods.goety.magic.DarkAltarSoulPort;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 让黑暗祭坛在受管诅咒笼没有原生灵魂时仍保持可运行状态。 */
@Mixin(targets = "com.Polarice3.Goety.common.blocks.entities.DarkAltarBlockEntity",
        remap = false)
public abstract class DarkAltarSoulSourceMixin {
    @Inject(method = "checkCage", at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$useVirtualSoulSource(CallbackInfoReturnable<Boolean> cir) {
        if (DarkAltarSoulPort.isActive(this)) {
            cir.setReturnValue(true);
        }
    }
}
