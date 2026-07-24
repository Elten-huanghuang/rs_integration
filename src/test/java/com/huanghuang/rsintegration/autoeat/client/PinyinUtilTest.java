package com.huanghuang.rsintegration.autoeat.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinyinUtilTest {

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
    void handlesNullAndEmptyInput() {
        assertTrue(PinyinUtil.toPinyin(null).isEmpty());
        assertTrue(PinyinUtil.toPinyinInitials("").isEmpty());
    }
}
