package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.unifiedgrid.client.UnifiedGridJeiTracker;
import com.huanghuang.rsintegration.unifiedgrid.client.UnifiedGridView;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.integration.jei.IngredientTracker;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.util.ItemStackKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = IngredientTracker.class, remap = false)
public abstract class UnifiedGridIngredientTrackerMixin {
    @Shadow private Map<ItemStackKey, Integer> storedItems;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rsi$trackMixedQuantities(GridContainerMenu menu, CallbackInfo ci) {
        if (menu.getScreenInfoProvider() instanceof GridScreen screen && screen.getView() instanceof UnifiedGridView view)
            UnifiedGridJeiTracker.attach(view, storedItems);
    }
}
