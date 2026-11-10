package com.huanghuang.rsintegration.mixin.jei;

import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = RecipesGui.class, remap = false)
public abstract class RecipesGuiMixin {

    @Invoker("updateLayout")
    public abstract void rsi$invokeUpdateLayout();
}
