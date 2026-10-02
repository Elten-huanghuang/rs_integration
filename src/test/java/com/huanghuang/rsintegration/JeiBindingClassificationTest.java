package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.mods.malum.MalumRSModule;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsNouveauRSModule;
import com.huanghuang.rsintegration.mods.biomancy.BiomancyRSModule;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRSModule;
import com.huanghuang.rsintegration.util.ModIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiBindingClassificationTest {

    @BeforeAll
    static void registerMalumTypes() {
        MalumRSModule.INSTANCE.registerModType();
        ArsNouveauRSModule.INSTANCE.registerModType();
        BiomancyRSModule.INSTANCE.registerModType();
        IronSpellBooksRSModule.INSTANCE.registerModType();
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
    void mapsBiomancyJeiCategoriesAndRecipeClassesToTheirOwnMachines() {
        String[][] mappings = {
                {ModIds.ID_BIOMANCY_DIGESTER, "digesting", "DigestingRecipe", "digester"},
                {ModIds.ID_BIOMANCY_BIO_LAB, "bio_brewing", "BioBrewingRecipe", "bio_lab"},
                {ModIds.ID_BIOMANCY_DECOMPOSER, "decomposing", "DecomposingRecipe", "decomposer"},
                {ModIds.ID_BIOMANCY_BIO_FORGE, "bio_forging", "BioForgingRecipe", "bio_forge"}
        };
        for (String[] mapping : mappings) {
            assertEquals(mapping[0], ModType.filterForJeiUid("biomancy:" + mapping[1]));
            assertEquals(mapping[0], ModType.filterForRecipeClass(
                    "com.github.elenterius.biomancy.crafting.recipe." + mapping[2]));
            assertEquals(mapping[0], ModType.fromBlockKey("block.biomancy." + mapping[3]).id());
            assertEquals(mapping[0], ModType.fromBlockKey(
                    mapping[0] + "||block.biomancy." + mapping[3]).id());
        }
        assertEquals(ModIds.ID_BIOMANCY_DIGESTER, ModType.filterForRecipeClass(
                "com.github.elenterius.biomancy.crafting.recipe.FoodDigestingRecipe"));
        assertEquals(ModIds.ID_BIOMANCY_DIGESTER, ModType.filterForRecipeClass(
                "com.github.elenterius.biomancy.crafting.recipe.StaticDigestingRecipe"));
    }

    @Test
    void mapsMarketCategoryToCanonicalBindingPrefix() {
        assertEquals("market", ModType.filterForJeiUid("farmingforblockheads:market"));
    }

    @Test
    void mapsAlchemistCustomPlusToItsCauldronBinding() {
        assertEquals(IronSpellBooksRSModule.ALCHEMIST_CAULDRON_TYPE,
                ModType.filterForJeiUid("rs_integration:alchemist_cauldron"));
        assertEquals(IronSpellBooksRSModule.ALCHEMIST_CAULDRON_TYPE,
                ModType.fromBlockKey("block.irons_spellbooks.alchemist_cauldron").id());
        assertEquals("gui.rs_integration.jei.irons_spellbooks_alchemist_cauldron",
                ModType.byId(IronSpellBooksRSModule.ALCHEMIST_CAULDRON_TYPE).jeiTooltipKey());
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
