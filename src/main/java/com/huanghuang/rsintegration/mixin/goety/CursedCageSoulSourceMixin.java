package com.huanghuang.rsintegration.mixin.goety;

import com.huanghuang.rsintegration.mods.goety.magic.GoetyVirtualSoulAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 诅咒笼上的统一灵魂账本覆盖。
 *
 * <p>黑暗祭坛、AoM 塔罗和灵魂烛台都通过诅咒笼读取/扣除灵魂，因此在这一层
 * 接管可以同时覆盖所有调用方，又不需要修改各自 tick 的调用点。</p>
 */
@Mixin(targets = "com.Polarice3.Goety.common.blocks.entities.CursedCageBlockEntity",
        remap = false)
public abstract class CursedCageSoulSourceMixin {
    @Inject(method = "getSouls", at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$readUnifiedSouls(CallbackInfoReturnable<Integer> cir) {
        int virtual = GoetyVirtualSoulAccess.souls(this);
        if (virtual >= 0) {
            cir.setReturnValue(virtual);
        }
    }

    @Inject(method = "decreaseSouls", at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$consumeUnifiedSouls(int amount, CallbackInfo ci) {
        if (GoetyVirtualSoulAccess.isActive(this)
                && GoetyVirtualSoulAccess.consume(this, amount)) {
            ci.cancel();
        }
    }
}
