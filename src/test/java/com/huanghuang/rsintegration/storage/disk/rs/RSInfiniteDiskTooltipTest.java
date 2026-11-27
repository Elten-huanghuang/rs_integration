package com.huanghuang.rsintegration.storage.disk.rs;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RSInfiniteDiskTooltipTest {
    @ParameterizedTest
    @ValueSource(strings = {"infinity_water_disk", "infinity_lava_disk"})
    void collapsedTooltipOnlyAddsTheShiftHint(String item) {
        Component description = Component.literal("description");
        List<Component> tooltip = new ArrayList<>(List.of(description));
        assertTrue(RSInfiniteDiskTooltip.appendStory(new ResourceLocation("rs_integration", item), tooltip, false));

        assertEquals(2, tooltip.size());
        assertEquals(description, tooltip.get(0));
        assertEquals("item.rs_integration." + item + ".lore.expand", translationKey(tooltip.get(1)));
    }

    @ParameterizedTest
    @CsvSource({"infinity_water_disk, 10", "infinity_lava_disk, 9"})
    void expandedTooltipAddsTheTitleAndStoryInOrder(String item, int lines) {
        List<Component> tooltip = new ArrayList<>();
        assertTrue(RSInfiniteDiskTooltip.appendStory(new ResourceLocation("rs_integration", item), tooltip, true));

        String lore = "item.rs_integration." + item + ".lore.";
        assertEquals(lines + 2, tooltip.size());
        assertEquals(Component.empty(), tooltip.get(0));
        assertEquals(lore + "title", translationKey(tooltip.get(1)));
        for (int line = 1; line <= lines; line++) {
            assertEquals(lore + line, translationKey(tooltip.get(line + 1)));
        }
    }

    @Test
    void unrelatedItemsDoNotGetAStory() {
        assertFalse(RSInfiniteDiskTooltip.appendStory(
                new ResourceLocation("refinedstorage", "creative_fluid_storage_disk"), List.of(), true));
    }

    private static String translationKey(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }
}
