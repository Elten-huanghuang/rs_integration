package com.huanghuang.rsintegration.mods.bd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 数量文本要与对应 BD 版本的写法一致（0.7 截断加小写 k，0.8 保留一位小数加大写 K）。 */
class BeyondDimensionsAmountFormatTest {
    @Test
    void modernVersionKeepsOneDecimalBelowTen() {
        assertEquals("0", BeyondDimensionsAmountFormat.formatCompact(0L));
        assertEquals("999", BeyondDimensionsAmountFormat.formatCompact(999L));
        assertEquals("1K", BeyondDimensionsAmountFormat.formatCompact(1_000L));
        assertEquals("1.2K", BeyondDimensionsAmountFormat.formatCompact(1_234L));
        assertEquals("9.9K", BeyondDimensionsAmountFormat.formatCompact(9_999L));
        assertEquals("10K", BeyondDimensionsAmountFormat.formatCompact(10_000L));
        assertEquals("999K", BeyondDimensionsAmountFormat.formatCompact(999_999L));
        assertEquals("1M", BeyondDimensionsAmountFormat.formatCompact(1_000_000L));
        assertEquals("1.2M", BeyondDimensionsAmountFormat.formatCompact(1_234_567L));
        assertEquals("1E", BeyondDimensionsAmountFormat.formatCompact(1_000_000_000_000_000_000L));
    }

    @Test
    void legacyVersionTruncatesWithLowercaseK() {
        assertEquals("999", BeyondDimensionsAmountFormat.formatCount(999L));
        assertEquals("1k", BeyondDimensionsAmountFormat.formatCount(1_000L));
        assertEquals("1k", BeyondDimensionsAmountFormat.formatCount(1_999L));
        assertEquals("999k", BeyondDimensionsAmountFormat.formatCount(999_999L));
        assertEquals("1M", BeyondDimensionsAmountFormat.formatCount(1_000_000L));
        assertEquals("1G", BeyondDimensionsAmountFormat.formatCount(1_000_000_000L));
    }

    @Test
    void unitsCoverTheWholeLongRange() {
        assertEquals("9.2E", BeyondDimensionsAmountFormat.formatCompact(Long.MAX_VALUE));
        assertEquals("9E", BeyondDimensionsAmountFormat.formatCount(Long.MAX_VALUE));
    }
}
