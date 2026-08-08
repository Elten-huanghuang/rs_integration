package com.huanghuang.rsintegration.crafting;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CraftPacketUtilsMissingMaterialNameTest {

    @Test
    void potionCharmTemplateSuppliesTheMissingPotionNameArgument() {
        Component name = CraftPacketUtils.missingMaterialName(
                "item.apotheosis.potion_charm");

        TranslatableContents charm = assertInstanceOf(
                TranslatableContents.class, name.getContents());
        assertEquals("item.apotheosis.potion_charm", charm.getKey());
        assertEquals(1, charm.getArgs().length);

        Component potionName = assertInstanceOf(Component.class, charm.getArgs()[0]);
        TranslatableContents potion = assertInstanceOf(
                TranslatableContents.class, potionName.getContents());
        assertEquals("item.minecraft.potion", potion.getKey());
    }

    @Test
    void ordinaryItemNameDoesNotReceiveSpuriousArguments() {
        Component name = CraftPacketUtils.missingMaterialName("item.minecraft.iron_ingot");

        TranslatableContents contents = assertInstanceOf(
                TranslatableContents.class, name.getContents());
        assertEquals("item.minecraft.iron_ingot", contents.getKey());
        assertEquals(0, contents.getArgs().length);
    }
}
