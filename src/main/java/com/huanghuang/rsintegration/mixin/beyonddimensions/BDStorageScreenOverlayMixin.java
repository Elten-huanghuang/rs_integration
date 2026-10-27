package com.huanghuang.rsintegration.mixin.beyonddimensions;

import com.huanghuang.rsintegration.machine.client.BeyondDimensionsMachineHubClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.8 起超越维度把终端与各工作台界面合并进 {@code client.ui.storage.StorageScreen}，
 * 旧的 {@code client.gui.BDBaseGUI} 已删除。这里在新终端界面重画 RSI 的机器中心入口、
 * 共振入口与收藏条，绘制时机与 0.7 一致：物品 tooltip 之前。
 */
@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.client.ui.storage.StorageScreen", remap = false)
public abstract class BDStorageScreenOverlayMixin {

    @Inject(method = {"renderTooltip", "m_280072_"}, at = @At("HEAD"), remap = false, require = 0)
    private void rsi$renderTerminalControlsBeforeTooltip(GuiGraphics graphics, int mouseX, int mouseY,
                                                         CallbackInfo ci) {
        BeyondDimensionsMachineHubClient.renderTerminalControlsBeforeTooltip(
                (Screen) (Object) this, graphics, mouseX, mouseY);
    }
}
