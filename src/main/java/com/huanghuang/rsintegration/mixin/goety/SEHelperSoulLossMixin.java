package com.huanghuang.rsintegration.mixin.goety;

import com.huanghuang.rsintegration.magic.GoetySoulRefill;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 在 Goety 公共灵魂消耗完成后尝试从绑定 RS 网络补货。 */
@Pseudo
@Mixin(targets = "com.Polarice3.Goety.utils.SEHelper", remap = false)
public abstract class SEHelperSoulLossMixin {
    @Inject(method = "decreaseSESouls", at = @At("HEAD"), require = 0)
    private static void rsi$captureSoulsBeforeLoss(
            Player player, int souls, CallbackInfo callback) {
        GoetySoulRefill.beginLoss(player);
    }

    @Inject(method = "decreaseSESouls", at = @At("RETURN"), require = 0)
    private static void rsi$refillAfterSoulLoss(
            Player player, int souls, CallbackInfoReturnable<Boolean> callback) {
        GoetySoulRefill.afterLoss(player);
    }
}
