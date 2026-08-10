package com.huanghuang.rsintegration.mods.rs;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolCarrotSearchStatusTest {
    @Test
    void positiveStatusWinsSharedSubstring() {
        assertEquals(SolCarrotSearchStatus.Query.EATEN,
                SolCarrotSearchStatus.classifyText(
                        "eaten", "eaten: assisted in adding hearts",
                        "not yet eaten! what does it taste like?"));
        assertEquals(SolCarrotSearchStatus.Query.EATEN,
                SolCarrotSearchStatus.classifyText(
                        "尝过", "已经尝过", "还没尝过"));
    }

    @Test
    void explicitNegativePhraseRemainsNegative() {
        assertEquals(SolCarrotSearchStatus.Query.NOT_EATEN,
                SolCarrotSearchStatus.classifyText(
                        "not yet", "eaten: assisted in adding hearts",
                        "not yet eaten! what does it taste like?"));
        assertEquals(SolCarrotSearchStatus.Query.NOT_EATEN,
                SolCarrotSearchStatus.classifyText(
                        "还没尝过", "已经尝过", "还没尝过"));
    }

    @Test
    void onlyPlayerOwnedTooltipLinesAreDynamic() {
        assertTrue(SolCarrotSearchStatus.isDynamicTooltip(
                Component.translatable("tooltip.solcarrot.hearty.not_eaten")));
        assertTrue(SolCarrotSearchStatus.isDynamicTooltip(
                Component.literal("prefix").append(
                        Component.translatable("tooltip.solcarrot.cheap.eaten"))));
        assertFalse(SolCarrotSearchStatus.isDynamicTooltip(
                Component.translatable("tooltip.solcarrot.disabled.blacklist")));
        assertFalse(SolCarrotSearchStatus.isDynamicTooltip(
                Component.translatable("tooltip.solcarrot.cheap")));
    }
}
