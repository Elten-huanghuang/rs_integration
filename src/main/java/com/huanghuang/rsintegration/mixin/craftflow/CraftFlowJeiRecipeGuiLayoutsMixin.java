package com.huanghuang.rsintegration.mixin.craftflow;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cancels CraftFlow's directly rendered special-recipe JEI buttons. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.recipes.RecipeGuiLayouts", remap = false, priority = 500)
public abstract class CraftFlowJeiRecipeGuiLayoutsMixin {
    @Inject(method = "craftflow$drawSpecialButtons", at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private void rsi$disableSpecialButtons(GuiGraphics graphics, int mouseX, int mouseY,
                                           CallbackInfoReturnable<?> originalCallback,
                                           CallbackInfo callback) {
        callback.cancel();
    }
}
