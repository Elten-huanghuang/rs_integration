package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltarBindingRegistryTest {

    @BeforeAll
    static void registerGoetyType() {
        if (ModType.byId("goety") == ModType.GENERIC) {
            ModType.register("goety", new String[0], new String[]{"goety"},
                    new String[]{"goety", "goety_altar", "goety_component"}, () -> null);
        }
        if (ModType.byId("goety_cursed_infuser") == ModType.GENERIC) {
            ModType.register("goety_cursed_infuser", new String[0], new String[]{"goety"},
                    new String[]{"goety_cursed_infuser"}, () -> null);
        }
        if (ModType.byId("pmmo_salvage") == ModType.GENERIC) {
            ModType.register("pmmo_salvage", new String[0], new String[]{"pmmo_salvage"},
                    new String[]{"pmmo_salvage"}, () -> null);
        }
        if (ModType.byId(ModIds.ID_ARS_APPARATUS) == ModType.GENERIC) {
            ModType.register(ModIds.ID_ARS_APPARATUS, new String[0], new String[]{"apparatus"},
                    new String[]{"apparatus"}, () -> null);
        }
        if (ModType.byId(ModIds.ID_ARS_IMBUEMENT) == ModType.GENERIC) {
            ModType.register(ModIds.ID_ARS_IMBUEMENT, new String[0], new String[]{"imbuement"},
                    new String[]{"imbuement"}, () -> null);
        }
    }

    @Test
    void goetyComponentsAreNotExecutableMachines() {
        ModType goety = ModType.byId("goety");

        assertTrue(AltarBindingRegistry.isExecutableBinding(goety, "goety"));
        assertTrue(AltarBindingRegistry.isExecutableBinding(goety, "goety_altar"));
        assertFalse(AltarBindingRegistry.isExecutableBinding(goety, "goety_component"));
        assertFalse(AltarBindingRegistry.isExecutableBinding(
                goety, "goety_component||block.goety.cursed_cage"));
        assertFalse(AltarBindingRegistry.isExecutableBinding(
                goety, "goety_component||block.goety.soul_candlestick"));
    }

    @Test
    void cursedInfuserRecipeFolderIsNotTreatedAsMachineSubtype() {
        ModType infuser = ModType.byId("goety_cursed_infuser");

        assertNull(AltarBindingRegistry.normalizeSubType("shade", infuser));
    }

    @Test
    void marketRecipeFolderIsNotTreatedAsMachineSubtype() {
        assertNull(AltarBindingRegistry.normalizeSubType(
                "market", ModType.FARMINGFORBLOCKHEADS_MARKET));
    }

    @Test
    void kubeJsGeneratedFolderIsNeverTreatedAsMachineSubtype() {
        assertNull(AltarBindingRegistry.normalizeSubType("kjs", ModType.GENERIC));
        assertNull(AltarBindingRegistry.normalizeSubType("kjs", ModType.byId("goety")));
        assertNull(AltarBindingRegistry.normalizeSubType("kjs", null));
    }

    @Test
    void pmmoSyntheticFolderDoesNotRestrictConfiguredSalvageBlockBinding() {
        assertNull(AltarBindingRegistry.normalizeSubType(
                "pmmo_salvage", ModType.byId("pmmo_salvage")));
    }

    @Test
    void arsExtensionsTierFoldersDoNotRestrictArsMachineBindings() {
        ResourceLocation lesserRecipe = new ResourceLocation(
                "ars_extensions", "lesser/ring_of_lesser_mana_regen");

        assertNull(AltarBindingRegistry.normalizeSubType(
                AltarBindingRegistry.recipeSubTypeHint(lesserRecipe),
                ModType.byId(ModIds.ID_ARS_APPARATUS)));
        assertNull(AltarBindingRegistry.normalizeSubType(
                "greater", ModType.byId(ModIds.ID_ARS_APPARATUS)));
        assertNull(AltarBindingRegistry.normalizeSubType(
                "ultimate", ModType.byId(ModIds.ID_ARS_APPARATUS)));
        assertNull(AltarBindingRegistry.normalizeSubType(
                "greater", ModType.byId(ModIds.ID_ARS_IMBUEMENT)));
    }

    @Test
    void scriptOwnedRecipeIdsDoNotExposeArbitraryMachineSubtypes() {
        assertNull(AltarBindingRegistry.recipeSubTypeHint(
                new ResourceLocation("crafttweaker", "custom_group/machine_recipe")));
        assertNull(AltarBindingRegistry.recipeSubTypeHint(
                new ResourceLocation("malum", "kjs/content_hash")));
        assertNull(AltarBindingRegistry.recipeSubTypeHint(
                new ResourceLocation("ars_nouveau", "kjs/71pk1d1jqrkd401c8e93sx7gy")));
        assertNull(AltarBindingRegistry.recipeSubTypeHint(null));
    }

    @Test
    void nativeRecipeFoldersStillExposeMachineSubtypes() {
        org.junit.jupiter.api.Assertions.assertEquals("wissen_crystallizer",
                AltarBindingRegistry.recipeSubTypeHint(new ResourceLocation(
                        "wizards_reborn", "wissen_crystallizer/earth_crystal_seed")));
    }

    @Test
    void leafSpellWorkstationsIgnoreRecipeGroupingPaths() {
        registerLeafType("iss_csw_spell_forge");
        registerLeafType("irons_spellbooks_scroll_forge");
        registerLeafType("irons_spellbooks_arcane_anvil");

        assertNull(AltarBindingRegistry.normalizeSubType("amalgamator",
                ModType.byId("iss_csw_spell_forge")));
        assertNull(AltarBindingRegistry.normalizeSubType("irons_spellbooks",
                ModType.byId("irons_spellbooks_scroll_forge")));
        assertNull(AltarBindingRegistry.normalizeSubType("irons_spellbooks",
                ModType.byId("irons_spellbooks_arcane_anvil")));
    }

    private static void registerLeafType(String id) {
        if (ModType.byId(id) == ModType.GENERIC) {
            ModType.register(id, new String[0], new String[]{id}, new String[]{id}, () -> null);
        }
    }
}
