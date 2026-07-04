package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.sidepanel.client.GuiNavStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
