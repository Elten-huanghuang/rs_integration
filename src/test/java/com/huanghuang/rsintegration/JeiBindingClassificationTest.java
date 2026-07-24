package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.mods.malum.MalumRSModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JeiBindingClassificationTest {

    @BeforeAll
    static void registerMalumTypes() {
        MalumRSModule.INSTANCE.registerModType();
    }

    @Test
    void mapsCurrentAndCompatibilityMalumCategoryIds() {
        assertEquals("spirit_crucible", ModType.filterForJeiUid("malum:spirit_focusing"));
        assertEquals("spirit_crucible", ModType.filterForJeiUid("malum:spirit_crucible"));
        assertEquals("runic_workbench", ModType.filterForJeiUid("malum:runeworking"));
        assertEquals("runic_workbench", ModType.filterForJeiUid("malum:runic_workbench"));
    }

    @Test
    void mapsMarketCategoryToCanonicalBindingPrefix() {
        assertEquals("market", ModType.filterForJeiUid("farmingforblockheads:market"));
    }
}
