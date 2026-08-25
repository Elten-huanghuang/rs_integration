package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.network.chat.Component;

/** Localized client feedback for an attempted missing-item bookmark. */
public final class QuestMissingBookmarkMessage {
    public enum BookmarkResult {
        ADDED,
        EXISTS,
        UNAVAILABLE
    }

    private QuestMissingBookmarkMessage() {}

    public static Component create(QuestMissingBookmarkPacket packet, BookmarkResult result) {
        String key = switch (result) {
            case ADDED -> "rsi.ftb_quest.missing_bookmarked";
            case EXISTS -> "rsi.ftb_quest.missing_already_bookmarked";
            case UNAVAILABLE -> "rsi.ftb_quest.missing";
        };
        return Component.translatable(key,
                packet.stack().getHoverName(), packet.missingCount());
    }
}
