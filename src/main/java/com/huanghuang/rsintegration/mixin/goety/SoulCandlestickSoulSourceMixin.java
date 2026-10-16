package com.huanghuang.rsintegration.mixin.goety;

import com.huanghuang.rsintegration.magic.GoetyVirtualSoulAccess;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 灵魂烛台把统一账本伪装成原生灵魂来源，兼容原版火盆和 AoM 火盆。 */
@Mixin(targets = "com.Polarice3.Goety.common.blocks.entities.SoulCandlestickBlockEntity",
        remap = false)
public abstract class SoulCandlestickSoulSourceMixin {
    @Inject(method = "checkCage", at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$useVirtualSoulSource(CallbackInfoReturnable<Boolean> cir) {
        if (GoetyVirtualSoulAccess.isActive(this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getSouls", at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$readUnifiedSouls(CallbackInfoReturnable<Integer> cir) {
        int virtual = GoetyVirtualSoulAccess.souls(this);
        if (virtual >= 0) {
            cir.setReturnValue(virtual);
        }
    }

    @Inject(method = "drainSouls", at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$consumeUnifiedSouls(int amount, BlockPos pos, CallbackInfo ci) {
        if (GoetyVirtualSoulAccess.isActive(this)
                && GoetyVirtualSoulAccess.consume(this, amount)) {
            ci.cancel();
        }
    }
}
