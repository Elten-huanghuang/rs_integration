package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.mods.rs.RSGridSearchCache;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import com.refinedmods.refinedstorage.screen.grid.view.GridViewImpl;
import com.refinedmods.refinedstorage.screen.grid.view.IGridView;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = GridViewImpl.class, remap = false)
public class GridViewImplSearchMixin {
    @Shadow @Final
    private GridScreen screen;

    @Inject(method = "forceSort", at = @At("HEAD"), cancellable = true)
    private void rsi$deferSpecialSearch(CallbackInfo ci) {
        IGridView view = (IGridView) (Object) this;
        if (!RSGridSearchCache.beforeForceSort(this.screen, view)) ci.cancel();
    }

    @Inject(method = "setStacks", at = @At("RETURN"))
    private void rsi$resetSearchIndex(List<IGridStack> stacks, CallbackInfo ci) {
        RSGridSearchCache.onGridReset(this.screen, (IGridView) (Object) this);
    }

    @Inject(method = "postChange", at = @At("RETURN"))
    private void rsi$updateSearchIndex(IGridStack stack, int quantity, CallbackInfo ci) {
        RSGridSearchCache.onGridDelta(this.screen, (IGridView) (Object) this, stack);
    }
}
