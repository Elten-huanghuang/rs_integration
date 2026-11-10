package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.crafting.plan.ClientRecipeExclusions;
import com.huanghuang.rsintegration.feature.villager.tradelock.client.VillagerTradeLockClient;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import org.spongepowered.asm.mixin.Mixin;

/**
 * JEI 书签列表的增删回调：村民锁交易刷新，以及递归禁用（拉黑）状态同步。
 *
 * <p>拉黑以书签为载体：红色书签表示“已收藏且被禁用”。玩家用快捷键 A 或直接点击书签
 * 取消收藏时，JEI 只会把书签从列表里移除（{@code toggleBookmark} 内部也是调用
 * {@code remove}），不会去动 RS 的偏好文件，于是会出现“书签不红了、但配方仍然被
 * 拉黑”的残留。这里在移除成功后补一次清理，让取消收藏同时撤销禁用。</p>
 */
@Mixin(value = BookmarkList.class, remap = false)
public abstract class BookmarkListChangeMixin {
    @WrapMethod(method = "add(Lmezz/jei/gui/bookmarks/IBookmark;)Z")
    private boolean rsIntegration$bookmarkAdded(IBookmark bookmark, Operation<Boolean> original) {
        boolean added = original.call(bookmark);
        if (added) VillagerTradeLockClient.markDirty();
        return added;
    }

    @WrapMethod(method = "remove(Lmezz/jei/gui/bookmarks/IBookmark;)Z")
    private boolean rsIntegration$bookmarkRemoved(IBookmark bookmark, Operation<Boolean> original) {
        boolean removed = original.call(bookmark);
        if (!removed) return false;
        VillagerTradeLockClient.markDirty();
        // 书签被移除时同步撤销该配方的递归禁用偏好
        if (bookmark instanceof RecipeBookmark<?, ?> recipeBookmark) {
            ClientRecipeExclusions.clear(recipeBookmark.getRecipeUid());
        }
        return true;
    }
}
