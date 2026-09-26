package com.huanghuang.rsintegration.mixin.refinedstorage;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.machine.MachineHubInputHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

@Mixin(value = com.refinedmods.refinedstorage.screen.grid.GridScreen.class, remap = false)
public abstract class GridScreenKeyboardMixin {

    @Inject(method = "m_7933_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$onKeyPressed(int keyCode, int scanCode, int modifiers,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (MachineHubInputHandler.isConsumingInput()) {
            boolean consumed = MachineHubInputHandler.keyPressed(keyCode);
            if (consumed) {
                cir.setReturnValue(true);
                return;
            }
        }

        // Machine Center button: Enter/Space → toggle Hub overlay
        if (MachineTabHandler.isMachineCenterHovered() && (keyCode == 257 || keyCode == 32)) {
            GuiEventListener focused = ((Screen) (Object) this).getFocused();
            if (!(focused instanceof EditBox)) {
                MachineTabHandler.toggleMachineCenter();
                cir.setReturnValue(true);
            }
            return;
        }

        // Resonance Backpack button: Enter/Space → open backpack GUI
        if (MachineTabHandler.isResonanceBackpackHovered() && (keyCode == 257 || keyCode == 32)) {
            GuiEventListener focused = ((Screen) (Object) this).getFocused();
            if (!(focused instanceof EditBox)) {
                MachineTabHandler.toggleResonanceBackpack();
                cir.setReturnValue(true);
            }
            return;
        }
    }

    @Inject(method = "m_5534_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$onCharTyped(char codePoint, int modifiers,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (MachineHubInputHandler.isConsumingInput()) {
            boolean consumed = MachineHubInputHandler.charTyped(codePoint, modifiers);
            if (consumed) {
                cir.setReturnValue(true);
            }
        }
    }
}
