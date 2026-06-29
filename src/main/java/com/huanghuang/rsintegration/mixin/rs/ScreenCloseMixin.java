package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.sidepanel.client.GuiNavStack;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link Screen#removed()} so that {@link GuiNavStack} can
 * restore the cached RS GridScreen when a machine GUI is closing.
 */
@Mixin(Screen.class)
public abstract class ScreenCloseMixin {

    @Inject(method = "removed", at = @At("HEAD"))
    private void rsi$onScreenRemoved(CallbackInfo ci) {
        GuiNavStack.onScreenRemoved((Screen) (Object) this);
    }
}
