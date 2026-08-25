package com.huanghuang.rsintegration.compat.ftbquests.client;

import com.huanghuang.rsintegration.compat.ftbquests.QuestMissingBookmarkPacket;
import com.huanghuang.rsintegration.compat.ftbquests.QuestMissingBookmarkMessage;
import com.huanghuang.rsintegration.mixin.jei.BookmarkOverlayAccessor;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

public final class FtbQuestMissingBookmarkClient {
    private FtbQuestMissingBookmarkClient() {}

    public static void accept(QuestMissingBookmarkPacket packet) {
        QuestMissingBookmarkMessage.BookmarkResult result = bookmark(packet.stack());
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(QuestMissingBookmarkMessage.create(packet, result), true);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static QuestMissingBookmarkMessage.BookmarkResult bookmark(ItemStack stack) {
        var runtime = RSJeiPlugin.getRuntime();
        if (stack.isEmpty() || runtime == null
                || !(runtime.getBookmarkOverlay() instanceof BookmarkOverlay overlay)) {
            return QuestMissingBookmarkMessage.BookmarkResult.UNAVAILABLE;
        }
        var typed = runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK,
                stack.copyWithCount(1));
        if (typed.isEmpty()) return QuestMissingBookmarkMessage.BookmarkResult.UNAVAILABLE;
        var bookmark = IngredientBookmark.create(typed.get(), runtime.getIngredientManager());
        boolean added = ((BookmarkOverlayAccessor) overlay)
                .rsIntegration$getBookmarkList().add(bookmark);
        return added ? QuestMissingBookmarkMessage.BookmarkResult.ADDED
                : QuestMissingBookmarkMessage.BookmarkResult.EXISTS;
    }
}
