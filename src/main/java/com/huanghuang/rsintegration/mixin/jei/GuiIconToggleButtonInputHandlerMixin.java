package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.compat.jei.RecipeBookmarkRightClickHandler;
import mezz.jei.gui.elements.GuiIconToggleButton;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.recipes.RecipeBookmarkButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 包装 JEI 按钮输入处理器，在左键过滤前接住配方书签右键。 */
@Mixin(value = GuiIconToggleButton.class, remap = false)
public abstract class GuiIconToggleButtonInputHandlerMixin {

    @Inject(method = "createInputHandler", at = @At("RETURN"), cancellable = true)
    private void rsIntegration$wrapRecipeBookmarkInput(
            CallbackInfoReturnable<IUserInputHandler> cir) {
        if ((Object) this instanceof RecipeBookmarkButton bookmarkButton
                && cir.getReturnValue() != null) {
            cir.setReturnValue(new RecipeBookmarkRightClickHandler(
                    bookmarkButton, cir.getReturnValue()));
        }
    }
}
