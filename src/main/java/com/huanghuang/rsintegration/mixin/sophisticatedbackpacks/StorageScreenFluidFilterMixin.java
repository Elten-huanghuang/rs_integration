package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase", remap = false)
public abstract class StorageScreenFluidFilterMixin {
    @Inject(method = "renderStack", at = @At("HEAD"), cancellable = true)
    private void rsi$renderFluidFilter(GuiGraphics graphics, int x, int y, ItemStack stack,
            boolean highlighted, String count, CallbackInfo ci) {
        if (!InkFluidSupport.isToken(stack)) return;
        if (highlighted) graphics.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
        InkFluidRenderer.render(graphics, stack, x, y);
        ci.cancel();
    }
}
