package com.huanghuang.rsintegration.crafting.quantity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactQuantityFormatTest {
    @Test
    void compactsLongQuantitiesWithSharedSiSuffixes() {
        assertEquals("0", CompactQuantityFormat.format(0));
        assertEquals("999", CompactQuantityFormat.format(999));
        assertEquals("1K", CompactQuantityFormat.format(1_000));
        assertEquals("1.5K", CompactQuantityFormat.format(1_500));
        assertEquals("999K", CompactQuantityFormat.format(999_999));
        assertEquals("1M", CompactQuantityFormat.format(1_000_000));
        assertEquals("2.1G", CompactQuantityFormat.format(2_100_000_000L));
        assertEquals("15T", CompactQuantityFormat.format(15_000_000_000_000L));
        assertEquals("1P", CompactQuantityFormat.format(1_000_000_000_000_000L));
        assertEquals("1E", CompactQuantityFormat.format(1_000_000_000_000_000_000L));
        assertEquals("9.2E", CompactQuantityFormat.format(Long.MAX_VALUE));
        assertEquals("-9.2E", CompactQuantityFormat.format(Long.MIN_VALUE));
    }

    @Test
    void positiveQuantitiesNeverExceedFourCharacters() {
        for (long value : new long[]{999, 1_000, 1_500, 999_999, 1_000_000,
                2_100_000_000L, Long.MAX_VALUE}) {
            assertTrue(CompactQuantityFormat.format(value).length() <= 4);
        }
    }
}
