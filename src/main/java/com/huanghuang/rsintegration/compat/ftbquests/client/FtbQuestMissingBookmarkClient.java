package com.huanghuang.rsintegration.compat.ftbquests.client;

import com.huanghuang.rsintegration.compat.ftbquests.QuestMissingBookmarkPacket;
import com.huanghuang.rsintegration.mixin.jei.BookmarkOverlayAccessor;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class FtbQuestMissingBookmarkClient {
    private FtbQuestMissingBookmarkClient() {}

    public static void accept(QuestMissingBookmarkPacket packet) {
        boolean bookmarked = bookmark(packet.stack());
        var player = Minecraft.getInstance().player;
        if (player != null) {
            String key = bookmarked ? "rsi.ftb_quest.missing_bookmarked" : "rsi.ftb_quest.missing";
            player.displayClientMessage(Component.translatable(key,
                    packet.missingCount(), packet.stack().getHoverName()), true);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean bookmark(ItemStack stack) {
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime == null || !(runtime.getBookmarkOverlay() instanceof BookmarkOverlay overlay)) return false;
        var typed = runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK,
                stack.copyWithCount(1));
        if (typed.isEmpty()) return false;
        var bookmark = IngredientBookmark.create(typed.get(), runtime.getIngredientManager());
        ((BookmarkOverlayAccessor) overlay).rsIntegration$getBookmarkList().add(bookmark);
        return true;
    }
}
