package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class QuestMissingBookmarkMessageTest extends BootstrapTest {

    @Test
    void missingMessageUsesItemNameBeforeCount() {
        QuestMissingBookmarkPacket packet = new QuestMissingBookmarkPacket(
                new ItemStack(Items.GRASS_BLOCK), 4);

        Component message = QuestMissingBookmarkMessage.create(
                packet, QuestMissingBookmarkMessage.BookmarkResult.ADDED);
        TranslatableContents contents = assertInstanceOf(
                TranslatableContents.class, message.getContents());

        assertEquals("rsi.ftb_quest.missing_bookmarked", contents.getKey());
        assertEquals(2, contents.getArgs().length);
        assertEquals(packet.stack().getHoverName(), contents.getArgs()[0]);
        assertEquals(4L, contents.getArgs()[1]);
    }
}
