package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.machine.MachineHubInputHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts keyboard events on the RS GridScreen to handle Hub / machine tab keys.
 * Target: com.refinedmods.refinedstorage.screen.grid.GridScreen
 */
@Mixin(value = com.refinedmods.refinedstorage.screen.grid.GridScreen.class, remap = false)
public abstract class GridScreenKeyboardMixin {

    @Inject(method = "m_7933_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$onKeyPressed(int keyCode, int scanCode, int modifiers,
                                   CallbackInfoReturnable<Boolean> cir) {
        // Hub overlay steals keyboard input first
        if (MachineHubInputHandler.isConsumingInput()) {
            boolean consumed = MachineHubInputHandler.keyPressed(keyCode);
            if (consumed) {
                cir.setReturnValue(true);
                return;
            }
        }

        if (MachineTabHandler.getHoveredTabIndex() < 0) return;
        if (keyCode != 257 && keyCode != 32) return;

        // Don't consume Enter/Space if a text field is focused
        GuiEventListener focused = ((net.minecraft.client.gui.screens.Screen) (Object) this).getFocused();
        if (focused instanceof net.minecraft.client.gui.components.EditBox) return;

        var machines = MachineTabHandler.getVisibleTabs();
        int idx = MachineTabHandler.getHoveredTabIndex();
        if (idx < machines.size()) {
            MachineTabHandler.onClick(machines.get(idx));
            cir.setReturnValue(true);
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
