package com.huanghuang.rsintegration.mixin.obscuretooltips;

import com.huanghuang.rsintegration.client.storage.DiskTooltipBackground;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 保留 RSI 自定义底板所需的原版提示框绘制和 Forge 事件。 */
@Pseudo
@Mixin(targets = "dev.obscuria.tooltips.config.ClientConfig", remap = false)
public abstract class ObscureTooltipConfigMixin {
    @Inject(method = "isIgnored(Lnet/minecraft/world/item/Item;)Z",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void rsi$keepCustomBackground(Item item, CallbackInfoReturnable<Boolean> cir) {
        if (RSIntegrationConfig.enabled(RSIntegrationConfig.CLIENT_SPEC,
                RSIntegrationConfig.TOOLTIP_OBSCURE_COMPAT)
                && DiskTooltipBackground.hasCustomBackground(item)) {
            cir.setReturnValue(true);
        }
    }
}
