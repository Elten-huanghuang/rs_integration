package com.huanghuang.rsintegration.mixin.jei;

import mezz.jei.gui.elements.GuiIconButton;
import mezz.jei.gui.elements.GuiIconToggleButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GuiIconToggleButton.class, remap = false)
public interface GuiIconToggleButtonAccessor {

    @Accessor
    GuiIconButton getButton();
}
