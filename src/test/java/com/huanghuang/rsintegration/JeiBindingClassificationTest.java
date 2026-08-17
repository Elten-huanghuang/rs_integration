package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.mods.malum.MalumRSModule;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsNouveauRSModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiBindingClassificationTest {

    @BeforeAll
    static void registerMalumTypes() {
        MalumRSModule.INSTANCE.registerModType();
        ArsNouveauRSModule.INSTANCE.registerModType();
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

    @Test
    void mapsBothArsGlyphJeiIdsToTheScribesTableBinding() {
        assertEquals("ars_nouveau_scribes_table",
                ModType.filterForJeiUid("ars_nouveau:glyph_recipe"));
        assertEquals("ars_nouveau_scribes_table",
                ModType.filterForJeiUid("ars_nouveau:glyph"));
        assertEquals("ars_nouveau_scribes_table",
                ModType.filterForRecipeClass(
                        "com.hollingsworth.arsnouveau.common.crafting.recipes.GlyphRecipe"));
    }
}
