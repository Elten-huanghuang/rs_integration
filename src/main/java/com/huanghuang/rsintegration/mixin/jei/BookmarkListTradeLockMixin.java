package com.huanghuang.rsintegration.mixin.jei;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.villager.tradelock.client.VillagerTradeLockClient;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.BookmarkList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BookmarkList.class, remap = false)
public abstract class BookmarkListTradeLockMixin {
    @Inject(method = "add", at = @At("RETURN"), remap = false)
    private void rsIntegration$bookmarkAdded(IBookmark bookmark,
                                             CallbackInfoReturnable<Boolean> callback) {
        if (Boolean.TRUE.equals(callback.getReturnValue())) VillagerTradeLockClient.markDirty();
    }

    @Inject(method = "remove", at = @At("RETURN"), remap = false)
    private void rsIntegration$bookmarkRemoved(IBookmark bookmark,
                                               CallbackInfoReturnable<Boolean> callback) {
        if (Boolean.TRUE.equals(callback.getReturnValue())) VillagerTradeLockClient.markDirty();
    }
}
