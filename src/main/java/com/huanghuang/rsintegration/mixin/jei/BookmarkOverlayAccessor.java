package com.huanghuang.rsintegration.mixin.jei;

import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BookmarkOverlay.class, remap = false)
public interface BookmarkOverlayAccessor {
    @Accessor("bookmarkList")
    BookmarkList rsIntegration$getBookmarkList();
}
