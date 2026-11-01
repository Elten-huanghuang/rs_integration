package com.huanghuang.rsintegration.feature.goety;

import com.huanghuang.rsintegration.feature.goety.client.BrewSearchIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrewSearchIndexTest {
    private final BrewSearchIndex index = new BrewSearchIndex(
            List.of("§a夜视药酿", "金胡萝卜", "minecraft:golden_carrot"));

    @Test
    void matchesLocalizedNameFullPinyinAndInitials() {
        for (String query : List.of("夜视", "yeshiyaoniang", "ysy", "jinhu", "jhlb", "GOLDEN_CARROT")) {
            assertTrue(index.matches(query), query);
        }
    }

    @Test
    void fuzzyMatchAllowsSkippedCharactersAndSeveralKeywords() {
        for (String query : List.of("夜酿", "ye niang", "夜视 carrot", "金卜", "  YS   carrot  ")) {
            assertTrue(index.matches(query), query);
        }
    }

    @Test
    void blankQueryShowsAllAndUnrelatedOrReversedTextDoesNotMatch() {
        assertTrue(index.matches(""));
        assertTrue(index.matches("   "));
        assertTrue(index.matches(null));
        assertFalse(index.matches("不存在"));
        assertFalse(index.matches("萝胡"));
        assertFalse(index.matches("夜视 diamond"));
    }
}
