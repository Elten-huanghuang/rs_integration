package com.huanghuang.rsintegration.mixin.craftflow;
import java.lang.reflect.Method;

import net.minecraftforge.client.event.ScreenEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps CraftFlow's duplicate food actions out of storage-terminal screens. */
@Pseudo
@Mixin(targets = "com.ybm.craftflow.client.FoodEatingButtons", remap = false)
public abstract class CraftFlowFoodEatingButtonsMixin {
    @Inject(method = "onScreenInit", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableFoodButtons(ScreenEvent.Init.Post event,
                                               CallbackInfo callback) {
        callback.cancel();
    }
}
