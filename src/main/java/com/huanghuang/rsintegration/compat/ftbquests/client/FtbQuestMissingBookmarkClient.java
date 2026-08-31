package com.huanghuang.rsintegration.compat.ftbquests.client;

import com.huanghuang.rsintegration.compat.ftbquests.QuestMissingBookmarkPacket;
import com.huanghuang.rsintegration.compat.ftbquests.QuestMissingBookmarkMessage;
import com.huanghuang.rsintegration.client.RecipeBrowserBridge;
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

    static QuestMissingBookmarkMessage.BookmarkResult bookmark(ItemStack stack) {
        return switch (RecipeBrowserBridge.addFavorite(stack)) {
            case ADDED -> QuestMissingBookmarkMessage.BookmarkResult.ADDED;
            case EXISTS -> QuestMissingBookmarkMessage.BookmarkResult.EXISTS;
            case UNAVAILABLE -> QuestMissingBookmarkMessage.BookmarkResult.UNAVAILABLE;
        };
    }
}
