package com.huanghuang.rsintegration.feature.ftbquests;

import com.huanghuang.rsintegration.feature.ftbquests.client.FtbQuestMissingBookmarkClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class QuestMissingBookmarkClientPacketHandler {
    private QuestMissingBookmarkClientPacketHandler() {}
    static void handle(QuestMissingBookmarkPacket packet) {
        FtbQuestMissingBookmarkClient.accept(packet);
    }
}
