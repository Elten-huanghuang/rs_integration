package com.huanghuang.rsintegration.mixin.craftflow;
import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Removes CraftFlow's shared JEI/EMI button registry and click targets. */
@Pseudo
@Mixin(targets = "com.ybm.craftflow.client.BlueButtonPositions", remap = false)
public abstract class CraftFlowBlueButtonPositionsMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableButtonRegistration(int x, int y, int width, int height,
                                                      Runnable handler, int normalColor,
                                                      int hoverColor, CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = {"getAll", "getColors"}, at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void rsi$hideButtons(CallbackInfoReturnable<List<int[]>> callback) {
        callback.setReturnValue(List.of());
    }

    @Inject(method = "hitTestIndex", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableIndexedHitTest(double mouseX, double mouseY,
                                                  CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(-1);
    }

    @Inject(method = "hitTest", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableHitTest(double mouseX, double mouseY,
                                           CallbackInfoReturnable<int[]> callback) {
        callback.setReturnValue(null);
    }

    @Inject(method = "triggerClick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableClick(int index, CallbackInfo callback) {
        callback.cancel();
    }
}
