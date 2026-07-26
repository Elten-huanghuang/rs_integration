package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.mods.malum.MalumRSModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(MalumRSModule.VOID_FAVOR_TYPE_ID,
                ModType.filterForJeiUid("malum:weeping_well"));
        assertEquals(MalumRSModule.VOID_FAVOR_TYPE_ID,
                ModType.filterForRecipeClass(
                        "com.sammy.malum.common.recipe.FavorOfTheVoidRecipe"));
        assertTrue(ModType.byId(MalumRSModule.VOID_FAVOR_TYPE_ID).isVirtual());
    }

    @Test
    void mapsMarketCategoryToCanonicalBindingPrefix() {
        assertEquals("market", ModType.filterForJeiUid("farmingforblockheads:market"));
    }
}
