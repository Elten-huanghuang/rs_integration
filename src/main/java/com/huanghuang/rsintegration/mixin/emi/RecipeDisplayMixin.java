package com.huanghuang.rsintegration.mixin.emi;

import com.huanghuang.rsintegration.compat.emi.EmiCraftButtonDecorator;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.screen.RecipeDisplay;
import dev.emi.emi.screen.WidgetGroup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RecipeDisplay.class, remap = false)
public abstract class RecipeDisplayMixin {
    @Shadow
    @Final
    public EmiRecipe recipe;

    @Inject(method = "getWidgets", at = @At("RETURN"), remap = false, require = 0)
    private void rsi$addCraftButtons(int x, int y, int width, int height,
                                     CallbackInfoReturnable<WidgetGroup> callback) {
        WidgetGroup widgets = callback.getReturnValue();
        if (recipe != null && widgets != null) {
            EmiCraftButtonDecorator.decorate(recipe, widgets);
        }
    }
}
