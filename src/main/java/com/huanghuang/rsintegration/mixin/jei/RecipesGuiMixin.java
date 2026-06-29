package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.sidepanel.client.AltarCraftButtons;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.spongepowered.asm.mixin.Unique;

@Mixin(value = RecipesGui.class, remap = false)
public class RecipesGuiMixin {

    @Unique
    private static long missLastLogged;

    @Inject(method = "m_6375_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$handleMouseClick(double mouseX, double mouseY, int button,
                                       CallbackInfoReturnable<Boolean> cir) {
        // Log every click unconditionally for debugging the Embers bug
        try {
            if (button == 0) {
                int mgSize = AltarCraftButtons.getMachineGuiPositions().size();
                int plusSize = AltarCraftButtons.getPositions().size();
                int mgIndex = AltarCraftButtons.hitTestMachineGui(mouseX, mouseY);
                if (mgIndex >= 0) {
                    RSIntegrationMod.LOGGER.info("[RSI-RecipesGui] MachineGUI hit: index={} at ({},{})", mgIndex, mouseX, mouseY);
                    AltarCraftButtons.triggerMachineGui(mgIndex);
                    cir.setReturnValue(true);
                    cir.cancel();
                    return;
                }
                int index = AltarCraftButtons.hitTest(mouseX, mouseY);
                if (index >= 0) {
                    RSIntegrationMod.LOGGER.info("[RSI-RecipesGui] Plus hit: index={} at ({},{}) modType={} recipeId={}",
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
                        RSIntegrationMod.LOGGER.info("[RSI-RecipesGui] Miss: ({},{}) mgBtns={} plusBtns={}",
                                (int) mouseX, (int) mouseY, mgSize, plusSize);
                        if (plusSize > 0) {
                            int[] p0 = AltarCraftButtons.getPositions().get(0);
                            RSIntegrationMod.LOGGER.info("[RSI-RecipesGui] Plus[0]=({},{},{},{}) last=({},{},{},{})",
                                    p0[0], p0[1], p0[2], p0[3],
                                    AltarCraftButtons.getPositions().get(plusSize - 1)[0],
                                    AltarCraftButtons.getPositions().get(plusSize - 1)[1],
                                    AltarCraftButtons.getPositions().get(plusSize - 1)[2],
                                    AltarCraftButtons.getPositions().get(plusSize - 1)[3]);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            RSIntegrationMod.LOGGER.error("[RSI-RecipesGui] rsi$handleMouseClick crashed:", ex);
        }
    }
}
