package com.huanghuang.rsintegration.mixin.farmandcharm;

import com.huanghuang.rsintegration.mods.farmandcharm.client.NativeRecipeBookButtonHost;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在共享配方书界面的实际渲染阶段同步按钮位置和显隐。 */
@Mixin(targets = "de.cristelknight.doapi.client.recipebook.screen.AbstractRecipeBookGUIScreen",
        remap = false)
@Pseudo
public abstract class NativeRecipeBookScreenMixin {

    @Inject(method = "m_88315_", at = @At("HEAD"), remap = false)
    private void rsi$updateNativeCraftButton(GuiGraphics graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo callback) {
        if ((Object) this instanceof NativeRecipeBookButtonHost host) {
            host.rsi$updateNativeCraftButton();
        }
    }
}
