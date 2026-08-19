package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.mixin.jei.BookmarkOverlayAccessor;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import net.minecraft.world.item.ItemStack;

/** JEI-only part of world pick fallback, loaded only when JEI is present. */
final class WorldPickJeiClient {
    private WorldPickJeiClient() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean bookmark(ItemStack stack) {
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime == null || !(runtime.getBookmarkOverlay() instanceof BookmarkOverlay overlay)) return false;
        var typed = runtime.getIngredientManager().createTypedIngredient(
                VanillaTypes.ITEM_STACK, stack.copyWithCount(1));
        if (typed.isEmpty()) return false;
        var bookmark = IngredientBookmark.create(typed.get(), runtime.getIngredientManager());
        ((BookmarkOverlayAccessor) overlay).rsIntegration$getBookmarkList().add(bookmark);
        return true;
    }
}
