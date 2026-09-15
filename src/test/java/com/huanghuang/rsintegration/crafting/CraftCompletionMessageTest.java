package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CraftCompletionMessageTest extends BootstrapTest {
    @Test
    void completionKeepsItemTranslationUnresolvedAcrossSerialization() {
        ItemStack output = new ItemStack(Items.DIAMOND);
        Component message = CraftPacketUtils.craftCompletedMessage(output, 64);
        Component decoded = Component.Serializer.fromJson(Component.Serializer.toJson(message));
        var content = assertInstanceOf(TranslatableContents.class, decoded.getContents());
        assertEquals("rsi.generic.craft_completed", content.getKey());
        Component name = assertInstanceOf(Component.class, content.getArgs()[0]);
        var translatedName = assertInstanceOf(TranslatableContents.class, name.getContents());
        assertEquals(output.getDescriptionId(), translatedName.getKey());
        assertEquals(64, ((TranslatableContents) message.getContents()).getArgs()[1]);
    }

    @Test
    void completionPreservesCustomNameWithoutChangingOutputNbt() {
        ItemStack output = new ItemStack(Items.DIAMOND, 4);
        output.setHoverName(Component.literal("Custom output"));
        var before = output.getTag().copy();
        var content = (TranslatableContents) CraftPacketUtils.craftCompletedMessage(output, 1).getContents();
        assertEquals(output.getHoverName(), content.getArgs()[0]);
        assertEquals(before, output.getTag());
        assertEquals(4, output.getCount());
    }

    @Test
    void unknownOutputUsesLocalizedFallbackInsteadOfRecipeIdOrAir() {
        var content = (TranslatableContents) CraftPacketUtils.craftCompletedMessage(ItemStack.EMPTY, 0).getContents();
        var name = (Component) content.getArgs()[0];
        assertEquals("rsi.plan.unknown_item", ((TranslatableContents) name.getContents()).getKey());
        assertEquals(1, content.getArgs()[1]);
    }
}
