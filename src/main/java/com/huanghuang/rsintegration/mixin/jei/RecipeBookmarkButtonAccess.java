package com.huanghuang.rsintegration.mixin.jei;

import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.recipes.RecipeBookmarkButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RecipeBookmarkButton.class, remap = false)
public interface RecipeBookmarkButtonAccess {

    @Accessor("bookmarks")
    BookmarkList rsi$getBookmarks();

    @Accessor("recipeBookmark")
    RecipeBookmark<?, ?> rsi$getRecipeBookmark();

    @Accessor("bookmarked")
    boolean rsi$isBookmarked();
}
