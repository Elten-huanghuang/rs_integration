package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorOpenButton;
import com.huanghuang.rsintegration.mods.rs.monitor.RemoteCraftingGridMenu;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GridScreen.class, remap = false)
public abstract class CraftingGridScreenMixin {
    @Inject(method = "onPostInit", at = @At("TAIL"), require = 1)
    private void rsi$addCraftingMonitorButton(int x, int y, CallbackInfo ci) {
        GridScreen screen = (GridScreen) (Object) this;
        if (screen.getGrid().getGridType() == GridType.CRAFTING
                && (screen.getMenu().getBlockEntity() != null || screen.getMenu() instanceof RemoteCraftingGridMenu)) {
            screen.addSideButton(new RsMonitorOpenButton(screen, screen));
        }
    }
}
