package com.huanghuang.rsintegration.crafting.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialSearchMatcherTest {

    @Test
    void matchesChineseDisplayNameAndPartialText() {
        assertTrue(MaterialSearchMatcher.matches("橡木木板", "minecraft:oak_planks", "木木"));
    }

    @Test
    void matchesRegistryNameIgnoringCase() {
        assertTrue(MaterialSearchMatcher.matches("橡木木板", "minecraft:oak_planks", "OAK_PLAN"));
    }

    @Test
    void matchesFullPinyinAndInitials() {
        assertTrue(MaterialSearchMatcher.matches("橡木木板", "minecraft:oak_planks", "xiangmumu"));
        assertTrue(MaterialSearchMatcher.matches("橡木木板", "minecraft:oak_planks", "xmmb"));
    }

    @Test
    void trimsQueryAndRejectsUnrelatedText() {
        assertTrue(MaterialSearchMatcher.matches("橡木木板", "minecraft:oak_planks", "  oak  "));
        assertFalse(MaterialSearchMatcher.matches("橡木木板", "minecraft:oak_planks", "birch"));
    }
}
