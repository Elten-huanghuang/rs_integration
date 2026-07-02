package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.sidepanel.client.GuiNavStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link Minecraft#setScreen(Screen)} instead of listening to
 * {@link Screen#removed()}.  When the game tries to close the current screen
 * ({@code screen == null}) we check whether a cached RS GridScreen should be
 * restored.  This avoids the state-machine fragility of tracking every
 * intermediate screen removal.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void rsi$interceptSetScreen(Screen newScreen, CallbackInfo ci) {
        if (newScreen == null) {
            Screen closing = ((Minecraft) (Object) this).screen;
            Screen restore = GuiNavStack.popRestoreTarget(closing);
            if (restore != null) {
                ci.cancel();
                ((Minecraft) (Object) this).setScreen(restore);
            }
        } else {
            GuiNavStack.onScreenChanged(newScreen);
        }
    }
}
