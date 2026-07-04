package com.huanghuang.rsintegration.mixin.minecraft;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor
    int getLeftPos();

    @Accessor
    int getTopPos();

    @Accessor
    int getImageWidth();
}
