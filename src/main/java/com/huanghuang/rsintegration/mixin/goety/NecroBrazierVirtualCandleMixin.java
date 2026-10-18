package com.huanghuang.rsintegration.mixin.goety;

import com.huanghuang.rsintegration.magic.BrazierSoulPort;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** 仅 RSI 递归合成期间，为无实体烛台的亡灵火盆补一个内存来源。 */
@Pseudo
@Mixin(targets = "com.Polarice3.Goety.common.blocks.entities.NecroBrazierBlockEntity",
        remap = false)
public abstract class NecroBrazierVirtualCandleMixin {
    @Shadow
    @Final
    private List<Object> candlestickBlockEntityList;

    @Shadow
    private int currentTime;

    @Inject(method = "findCandlesticks", at = @At("RETURN"), require = 0)
    private void rsi$addRecursiveCraftCandle(CallbackInfo callback) {
        if (!candlestickBlockEntityList.isEmpty()) return;
        Object virtualCandle = BrazierSoulPort.virtualCandle(this);
        if (virtualCandle != null) {
            candlestickBlockEntityList.add(virtualCandle);
        }
    }

    @Inject(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lcom/Polarice3/Goety/common/crafting/BrazierRecipe;getSoulCost()I"),
            require = 0)
    private void rsi$accelerateRecursiveCraft(CallbackInfo callback) {
        int extraSpeed = BrazierSoulPort.virtualCandleSpeed(this) - 1;
        if (extraSpeed <= 0) return;
        extraSpeed = Math.min(extraSpeed, BrazierSoulPort.souls(this));
        if (extraSpeed > 0 && BrazierSoulPort.consume(this, extraSpeed)) {
            currentTime += extraSpeed;
        }
    }
}
