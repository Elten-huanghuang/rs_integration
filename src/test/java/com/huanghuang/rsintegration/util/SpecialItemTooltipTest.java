package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.feature.experience.ExperienceWandSettings;
import com.huanghuang.rsintegration.feature.experience.ResonantExperienceWandItem;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderTerminalItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class SpecialItemTooltipTest extends BootstrapTest {
    private static final ExperienceWandSettings SETTINGS = new ExperienceWandSettings(
            ExperienceWandSettings.Mode.KEEP, 42, true, 3);

    @Test
    void collapsedTooltipsShowShiftHintsAndKeepRealOpeningGestures() {
        var wand = new ArrayList<Component>();
        SpecialItemTooltip.appendExperience(wand, SETTINGS, false);
        assertTrue(contains(wand, "experience.rs_integration.open"));
        assertTrue(contains(wand, "experience.rs_integration.tooltip.expand"));
        assertFalse(contains(wand, "experience.rs_integration.tooltip.detail_1"));
        TranslatableContents target = (TranslatableContents) wand.get(1).getContents();
        assertEquals(42, target.getArgs()[0]);
        assertTrue(contains(wand, "experience.rs_integration.mending"));

        var food = new ArrayList<Component>();
        SpecialItemTooltip.appendFood(food, false);
        assertTrue(contains(food, "item.rs_integration.order_terminal.tip"));
        assertTrue(contains(food, "item.rs_integration.order_terminal.setup"));
        assertTrue(contains(food, "item.rs_integration.order_terminal.tooltip.expand"));
        assertFalse(contains(food, "item.rs_integration.order_terminal.tooltip.detail_1"));
    }

    @Test
    void holdingShiftExpandsBothGuidesWithoutLosingSettingsOrUseHints() {
        var wand = new ArrayList<Component>();
        SpecialItemTooltip.appendExperience(wand, SETTINGS, true);
        assertTrue(contains(wand, "experience.rs_integration.open"));
        assertFalse(contains(wand, "experience.rs_integration.tooltip.expand"));
        for (int i = 1; i <= 4; i++) {
            assertTrue(contains(wand, "experience.rs_integration.tooltip.detail_" + i));
        }

        var food = new ArrayList<Component>();
        SpecialItemTooltip.appendFood(food, true);
        assertTrue(contains(food, "item.rs_integration.order_terminal.tip"));
        assertFalse(contains(food, "item.rs_integration.order_terminal.tooltip.expand"));
        for (int i = 1; i <= 3; i++) {
            assertTrue(contains(food, "item.rs_integration.order_terminal.tooltip.detail_" + i));
        }
    }

    @Test
    void inactiveMendingIsNotReportedAsEnabled() {
        var tooltip = new ArrayList<Component>();
        SpecialItemTooltip.appendExperience(tooltip, new ExperienceWandSettings(
                ExperienceWandSettings.Mode.OFF, 30, false, 1), false);
        assertFalse(contains(tooltip, "experience.rs_integration.mending"));
    }

    @Test
    void itemEntrypointsReadShiftStateAndNamesUseThemedColors() {
        // 仅运行真实的名称与悬停入口，避免在已冻结的原版注册表中构造新物品。
        var wand = mock(ResonantExperienceWandItem.class, CALLS_REAL_METHODS);
        var food = mock(FoodOrderTerminalItem.class, CALLS_REAL_METHODS);
        doReturn("item.rs_integration.resonant_experience_wand").when(wand).getDescriptionId();
        doReturn("item.rs_integration.order_terminal").when(food).getDescriptionId();
        assertNotNull(wand.getName(ItemStack.EMPTY).getStyle().getColor());
        assertNotNull(food.getName(ItemStack.EMPTY).getStyle().getColor());
        try (var screen = mockStatic(Screen.class)) {
            for (boolean expanded : new boolean[]{false, true}) {
                screen.when(Screen::hasShiftDown).thenReturn(expanded);
                var wandLines = new ArrayList<Component>();
                var foodLines = new ArrayList<Component>();
                wand.appendHoverText(ItemStack.EMPTY, null, wandLines, TooltipFlag.Default.NORMAL);
                food.appendHoverText(ItemStack.EMPTY, null, foodLines, TooltipFlag.Default.NORMAL);
                assertEquals(!expanded, contains(wandLines, "experience.rs_integration.tooltip.expand"));
                assertEquals(!expanded, contains(foodLines, "item.rs_integration.order_terminal.tooltip.expand"));
                assertEquals(expanded, contains(wandLines, "experience.rs_integration.tooltip.detail_4"));
                assertEquals(expanded, contains(foodLines, "item.rs_integration.order_terminal.tooltip.detail_3"));
                assertThemed(wandLines);
                assertThemed(foodLines);
            }
        }
    }

    private static void assertThemed(List<Component> lines) {
        for (Component line : lines) {
            line.visit((style, text) -> {
                if (!text.isBlank()) assertNotNull(style.getColor(), text);
                return Optional.empty();
            }, Style.EMPTY);
        }
    }

    private static boolean contains(List<Component> tooltip, String key) {
        String text = Component.translatable(key).getString();
        return tooltip.stream().anyMatch(line -> line.getString().contains(text));
    }
}
