package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.mods.goety.AltarCraftButtons;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RecipesGui.class, remap = false)
public class RecipesGuiMixin {

    @Inject(method = "m_6375_", at = @At("HEAD"), cancellable = true)
    private void rsi$handleMouseClick(double mouseX, double mouseY, int button,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (button == 0) {
            int index = AltarCraftButtons.hitTest(mouseX, mouseY);
            if (index >= 0) {
                AltarCraftButtons.triggerClick(index);
                cir.setReturnValue(true);
                cir.cancel();
            }
        }
    }
}
