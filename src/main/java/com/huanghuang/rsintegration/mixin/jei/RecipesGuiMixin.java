package com.huanghuang.rsintegration.mixin.jei;

import java.lang.reflect.Method;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.client.recipe.AltarCraftButtons;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.spongepowered.asm.mixin.Unique;

@Mixin(value = RecipesGui.class, remap = false)
public abstract class RecipesGuiMixin {

    @Invoker("updateLayout")
    public abstract void rsi$invokeUpdateLayout();

    @Unique
    private static long missLastLogged;

    @Inject(method = "m_6375_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$handleMouseClick(double mouseX, double mouseY, int button,
                                       CallbackInfoReturnable<Boolean> cir) {
        // Log every click unconditionally for debugging the Embers bug
        try {
            if (button == 0) {
                int mgSize = AltarCraftButtons.getMachineGuiCount();
                int plusSize = AltarCraftButtons.getButtonCount();
                int mgIndex = AltarCraftButtons.hitTestMachineGui(mouseX, mouseY);
                if (mgIndex >= 0) {
                    RSIntegrationMod.LOGGER.debug("[RSI-RecipesGui] MachineGUI hit: index={} at ({},{})", mgIndex, mouseX, mouseY);
                    AltarCraftButtons.triggerMachineGui(mgIndex);
                    cir.setReturnValue(true);
                    cir.cancel();
                    return;
                }
                int index = AltarCraftButtons.hitTest(mouseX, mouseY);
                if (index >= 0) {
                    RSIntegrationMod.LOGGER.debug("[RSI-RecipesGui] Plus hit: index={} at ({},{}) modType={} recipeId={}",
                            index, mouseX, mouseY,
                            AltarCraftButtons.getButtonData(index) != null ? AltarCraftButtons.getButtonData(index).modType() : "null",
                            AltarCraftButtons.getButtonData(index) != null ? AltarCraftButtons.getButtonData(index).recipeId() : "null");
                    try {
                        AltarCraftButtons.triggerClick(index);
                    } catch (Exception ex) {
                        RSIntegrationMod.LOGGER.error("[RSI-RecipesGui] triggerClick threw:", ex);
                    }
                    cir.setReturnValue(true);
                    cir.cancel();
                } else {
                    // Miss — log once per second to avoid spam
                    long t = System.currentTimeMillis();
                    if (missLastLogged == 0 || t - missLastLogged > 1000) {
                        missLastLogged = t;
                        RSIntegrationMod.LOGGER.trace("[RSI-RecipesGui] Miss: ({},{}) mgBtns={} plusBtns={}",
                                (int) mouseX, (int) mouseY, mgSize, plusSize);
                        if (plusSize > 0) {
                            var p0 = AltarCraftButtons.getBounds(0);
                            var last = AltarCraftButtons.getBounds(plusSize - 1);
                            RSIntegrationMod.LOGGER.trace("[RSI-RecipesGui] Plus[0]=({},{},{},{}) last=({},{},{},{})",
                                    p0.x(), p0.y(), p0.width(), p0.height(),
                                    last.x(), last.y(), last.width(), last.height());
                        }
                    }
                }
            }
        } catch (Exception ex) {
            RSIntegrationMod.LOGGER.error("[RSI-RecipesGui] rsi$handleMouseClick crashed:", ex);
        }
    }
}
