package com.huanghuang.rsintegration.mixin.beyonddimensions;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.machine.BeyondDimensionsMachineHubClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Places RSI's BD terminal controls below the terminal's item tooltip. */
@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.client.gui.BDBaseGUI", remap = false)
public abstract class BDBaseGuiOverlayMixin {

    @Inject(method = {"renderTooltip", "m_280072_"}, at = @At("HEAD"), remap = false, require = 0)
    private void rsi$renderTerminalControlsBeforeTooltip(GuiGraphics graphics, int mouseX, int mouseY,
                                                          CallbackInfo ci) {
        BeyondDimensionsMachineHubClient.renderTerminalControlsBeforeTooltip(
                (Screen) (Object) this, graphics, mouseX, mouseY);
    }
}
