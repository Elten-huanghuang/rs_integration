package com.huanghuang.rsintegration.mixin.modernui;

import com.huanghuang.rsintegration.client.storage.DiskTooltipBackground;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderTooltipEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 同时跳过提前绘制和取消事件，让 RSI 底板继续走 Forge 原版绘制流程。 */
@Pseudo
@Mixin(targets = "icyllis.modernui.mc.forge.UIManagerForge", remap = false)
public abstract class ModernUITooltipMixin {
    @Inject(method = {
            "onRenderTooltipH(Lnet/minecraftforge/client/event/RenderTooltipEvent$Pre;)V",
            "onRenderTooltipL(Lnet/minecraftforge/client/event/RenderTooltipEvent$Pre;)V"
    }, at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void rsi$keepCustomBackground(RenderTooltipEvent.Pre event, CallbackInfo ci) {
        if (!RSIntegrationConfig.enabled(RSIntegrationConfig.CLIENT_SPEC,
                RSIntegrationConfig.TOOLTIP_MODERN_UI_COMPAT)) return;
        ItemStack stack = event.getItemStack();
        if (!stack.isEmpty() && DiskTooltipBackground.hasCustomBackground(stack.getItem())) {
            ci.cancel();
        }
    }
}
