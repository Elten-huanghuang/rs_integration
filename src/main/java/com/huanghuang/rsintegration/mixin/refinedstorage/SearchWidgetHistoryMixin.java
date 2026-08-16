package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.mods.rs.recentsearch.RecentSearchClient;
import com.refinedmods.refinedstorage.screen.widget.SearchWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SearchWidget.class, remap = false)
public abstract class SearchWidgetHistoryMixin {
    @Inject(method = "saveHistory", at = @At("HEAD"), remap = false)
    private void rsi$recordRecentSearch(CallbackInfo ci) {
        RecentSearchClient.recordNativeSearch((SearchWidget) (Object) this);
    }
}
