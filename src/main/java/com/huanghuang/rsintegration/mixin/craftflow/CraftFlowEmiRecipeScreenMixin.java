package com.huanghuang.rsintegration.mixin.craftflow;
import java.lang.reflect.Method;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels the draw handler merged into EMI by CraftFlow's higher-priority mixin. */
@Pseudo
@Mixin(targets = "dev.emi.emi.screen.RecipeScreen", remap = false, priority = 500)
public abstract class CraftFlowEmiRecipeScreenMixin {
    @Inject(method = "craftflow$draw", at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private void rsi$disableRecipeButton(GuiGraphics graphics, int mouseX, int mouseY,
                                         float partialTick, CallbackInfo originalCallback,
                                         CallbackInfo callback) {
        callback.cancel();
    }
}
