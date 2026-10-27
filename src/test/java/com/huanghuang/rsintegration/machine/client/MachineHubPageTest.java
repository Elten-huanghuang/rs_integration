package com.huanghuang.rsintegration.machine.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 侧边栏三个功能键（机器中心、共振背包、自动进食）的纯逻辑校验：
 * 图标字符画必须规整、互不相同的标识，且在不满足 0.8 条件时调用不能抛异常。
 */
class MachineHubPageTest {
    @Test
    void iconArtIsRectangularAndOnlyUsesPixelCharacters() {
        assertValidIcon(MachineHubPage.machineIconArt());
        assertValidIcon(MachineHubPage.backpackIconArt());
        assertValidIcon(MachineHubPage.autoEatIconArt());
    }

    @Test
    void iconsUseTheSameCanvasSize() {
        String[] machine = MachineHubPage.machineIconArt();
        String[] backpack = MachineHubPage.backpackIconArt();
        String[] autoEat = MachineHubPage.autoEatIconArt();
        assertEquals(machine.length, backpack.length, "图标的行数必须一致");
        assertEquals(machine[0].length(), backpack[0].length(), "图标的宽度必须一致");
        assertEquals(machine.length, autoEat.length, "自动进食图标的行数必须一致");
        assertEquals(machine[0].length(), autoEat[0].length(), "自动进食图标的宽度必须一致");
    }

    @Test
    void everyRailButtonHasItsOwnPageId() {
        assertEquals(3, MachineHubPage.pageIds().size(), "三个功能键各占一个页签 id");
        assertEquals(MachineHubPage.pageIds().size(),
                MachineHubPage.pageIds().stream().distinct().count(),
                "页签 id 不能重复");
    }

    @Test
    void pageIdsAreNamespacedUnderTheMod() {
        assertTrue(MachineHubPage.pageIds().stream()
                        .allMatch(id -> id.startsWith("rs_integration:")),
                "页签 id 必须带本模组命名空间");
    }

    @Test
    void actionKeysMatchTheirLabels() {
        assertTrue(MachineHubPage.actionKeys().contains("rsi.machine_hub.page"));
        assertTrue(MachineHubPage.actionKeys().contains("rsi.resonance_backpack.page"));
        assertTrue(MachineHubPage.actionKeys().contains("rsi.auto_eat.page"));
    }

    @Test
    void unknownRailButtonIsIgnored() {
        assertFalse(MachineHubPage.handleRailClick("beyonddimensions:settings"));
        assertFalse(MachineHubPage.handleRailClick(""));
        assertFalse(MachineHubPage.handleRailClick(null));
    }

    @Test
    void installingWithoutModernBeyondDimensionsIsHarmless() {
        assertDoesNotThrow(MachineHubPage::install);
        assertDoesNotThrow(MachineHubPage::reset);
        assertDoesNotThrow(MachineHubPage::isOpen);
    }

    private static void assertValidIcon(String[] art) {
        assertTrue(art.length > 0, "图标至少要有一行");
        int width = art[0].length();
        assertTrue(width > 0, "图标宽度不能为 0");
        for (String row : art) {
            assertEquals(width, row.length(),
                    "图标每行长度必须一致，实际出现 " + row.length() + " 与 " + width);
            for (int index = 0; index < row.length(); index++) {
                char pixel = row.charAt(index);
                assertTrue(pixel == '#' || pixel == '.',
                        "图标只能使用 '#' 与 '.'，实际出现 '" + pixel + "'");
            }
        }
    }
}
