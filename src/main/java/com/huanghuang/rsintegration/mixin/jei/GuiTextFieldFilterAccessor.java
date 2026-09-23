package com.huanghuang.rsintegration.mixin.jei;

import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.input.GuiTextFieldFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GuiTextFieldFilter.class, remap = false)
public interface GuiTextFieldFilterAccessor {
    @Accessor("area")
    ImmutableRect2i rsi$getArea();
}
