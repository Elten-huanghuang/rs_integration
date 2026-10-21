package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.feature.unifiedgrid.client.UnifiedGridClient;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GridScreen.class, remap = false)
public abstract class UnifiedGridScreenMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void rsi$updateMixedView(int x, int y, CallbackInfo ci) {
        if (((GridScreen) (Object) this).getGrid().getGridType() == GridType.PATTERN) return;
        UnifiedGridClient.tick((GridScreen) (Object) this);
    }

    @Inject(method = "m_6375_", at = @At(value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/network/grid/IGrid;isGridActive()Z"), cancellable = true)
    private void rsi$dispatchResourceClick(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        if (((GridScreen) (Object) this).getGrid().getGridType() == GridType.PATTERN) return;
        if (UnifiedGridClient.click((GridScreen) (Object) this, x, y, button)) cir.setReturnValue(true);
    }

    @Inject(method = "m_6050_", at = @At("HEAD"), cancellable = true)
    private void rsi$dispatchResourceScroll(double x, double y, double delta, CallbackInfoReturnable<Boolean> cir) {
        if (((GridScreen) (Object) this).getGrid().getGridType() == GridType.PATTERN) return;
        if (UnifiedGridClient.scroll((GridScreen) (Object) this, x, y, delta)) cir.setReturnValue(true);
    }
}
