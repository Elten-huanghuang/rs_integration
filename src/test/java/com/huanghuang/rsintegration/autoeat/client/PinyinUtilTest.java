package com.huanghuang.rsintegration.autoeat.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinyinUtilTest {

    @AfterEach
    void clearCache() {
        PinyinUtil.clearCacheForTesting();
    }

    @Test
    void convertsChineseTextToLowercasePinyin() {
        assertEquals("pingguo", PinyinUtil.toPinyin("\u82f9\u679c"));
        assertEquals("pg", PinyinUtil.toPinyinInitials("\u82f9\u679c"));
    }

    @Test
    void preservesSearchableLatinAndNumericText() {
        assertEquals("rsgrid2", PinyinUtil.toPinyin("RSGrid2"));
        assertEquals("rsgrid2", PinyinUtil.toPinyinInitials("RSGrid2"));
    }

    @Test
    void usesPhraseContextForPolyphonicCharacters() {
        assertEquals("chongqing", PinyinUtil.toPinyin("\u91cd\u5e86"));
        assertEquals("cq", PinyinUtil.toPinyinInitials("\u91cd\u5e86"));
    }

    @Test
    void keepsLegacyVSpellingForUWithDiaeresis() {
        assertEquals("lvbaoshi", PinyinUtil.toPinyin("\u7eff\u5b9d\u77f3"));
        assertEquals("nvwu", PinyinUtil.toPinyin("\u5973\u5deb"));
        assertEquals("lbs", PinyinUtil.toPinyinInitials("\u7eff\u5b9d\u77f3"));
    }

    @Test
    void preservesMixedTextAndSourcePunctuation() {
        assertEquals("rs-2pingguo!", PinyinUtil.toPinyin("RS-2\u82f9\u679c!"));
        assertEquals("rs-2pg!", PinyinUtil.toPinyinInitials("RS-2\u82f9\u679c!"));
    }

    @Test
    void handlesRareHanWithoutDroppingCharacters() {
        assertTrue(!PinyinUtil.toPinyin("\u9f98").isEmpty());
        assertTrue(!PinyinUtil.toPinyinInitials("\u9f98").isEmpty());
    }

    @Test
    void computesBothFormsOnceAndBoundsTheCache() {
        assertEquals("pingguo", PinyinUtil.toPinyin("\u82f9\u679c"));
        assertEquals("pg", PinyinUtil.toPinyinInitials("\u82f9\u679c"));
        assertEquals(1, PinyinUtil.cacheSizeForTesting());

        for (int i = 0; i < PinyinUtil.CACHE_LIMIT + 20; i++) {
            PinyinUtil.toPinyin("\u82f9\u679c" + i);
        }
        assertEquals(PinyinUtil.CACHE_LIMIT, PinyinUtil.cacheSizeForTesting());
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertTrue(PinyinUtil.toPinyin(null).isEmpty());
        assertTrue(PinyinUtil.toPinyinInitials("").isEmpty());
    }
}
