package com.huanghuang.rsintegration.mixin.craftflow;
import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Disables every optional CraftFlow integration while RSI owns the shared feature surface. */
@Pseudo
@Mixin(targets = "com.ybm.craftflow.config.CraftFlowConfig", remap = false)
public abstract class CraftFlowConfigMixin {
    @Inject(method = "isEnabled", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableIntegration(String integration,
                                               CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(false);
    }
}
