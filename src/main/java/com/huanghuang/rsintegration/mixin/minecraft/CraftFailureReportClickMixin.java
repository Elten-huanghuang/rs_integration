package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.crafting.CraftFailureClientCommands;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class CraftFailureReportClickMixin {
    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsIntegration$openLocalFailureReport(Style style, CallbackInfoReturnable<Boolean> callback) {
        if (!Screen.hasShiftDown() && CraftFailureClientCommands.handleClick(style)) callback.setReturnValue(true);
    }
}
