package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        if (ModType.byId(ModIds.ID_FD_SKILLET) == ModType.GENERIC) {
            ModType.register(ModIds.ID_FD_SKILLET, new String[0],
                    new String[]{"skillet", "campfire"},
                    new String[]{ModIds.ID_FD_SKILLET}, () -> null);
        }
        if (ModType.byId("farmersdelight_cooking_pot") == ModType.GENERIC) {
            ModType.register("farmersdelight_cooking_pot", new String[0],
                    new String[]{"cooking_pot"},
                    new String[]{"farmersdelight_cooking_pot"}, () -> null);
        }
        if (ModType.byId(ModIds.ID_MD_COPPER_POT) == ModType.GENERIC) {
            ModType.register(ModIds.ID_MD_COPPER_POT, new String[0],
                    new String[]{"copper_pot"},
                    new String[]{ModIds.ID_MD_COPPER_POT}, () -> null);
        }
        if (ModType.byId(ModIds.ID_FD_CUTTING_BOARD) == ModType.GENERIC) {
            ModType.register(ModIds.ID_FD_CUTTING_BOARD, new String[0],
                    new String[]{"cutting_board"},
                    new String[]{ModIds.ID_FD_CUTTING_BOARD}, () -> null);
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
    void birdcageRecipeFolderIsNotTreatedAsMachineSubtype() {
        registerLeafType("crockpot_birdcage");
        assertNull(AltarBindingRegistry.normalizeSubType(
                "parrot_feeding", ModType.byId("crockpot_birdcage")));
    }

    @Test
    void biomancyRecipeFoldersDoNotOverrideConcreteMachineTypes() {
        String[][] types = {
                {ModIds.ID_BIOMANCY_DIGESTER, "digesting"},
                {ModIds.ID_BIOMANCY_BIO_LAB, "bio_brewing"},
                {ModIds.ID_BIOMANCY_DECOMPOSER, "decomposing"},
                {ModIds.ID_BIOMANCY_BIO_FORGE, "bio_forging"}
        };
        for (String[] mapping : types) {
            registerLeafType(mapping[0]);
            ModType type = ModType.byId(mapping[0]);
            assertNull(AltarBindingRegistry.normalizeSubType(mapping[1], type));
            assertNull(AltarBindingRegistry.normalizeSubType("third_party", type));
            Recipe<?> recipe = new BiomancyFolderRecipe(new ResourceLocation("biomancy", mapping[1] + "/test"));
            assertNull(AltarBindingRegistry.recipeMachineSubType(recipe, type));
        }
    }

    @Test
    void eidolonMachineCategoriesDistinguishBrazierAndCrucible() {
        registerLeafType(ModIds.EIDOLON);
        assertEquals("brazier", AltarBindingRegistry.normalizeSubType(
                "rituals", ModType.byId(ModIds.EIDOLON)));
        assertEquals("brazier", AltarBindingRegistry.normalizeSubType(
                "ritual", ModType.byId(ModIds.EIDOLON)));
        assertEquals("crucible", AltarBindingRegistry.normalizeSubType(
                "crucible", ModType.byId(ModIds.EIDOLON)));
    }

    @Test
    void avaritiaRecipeFoldersDoNotPretendToBeMachineTiers() {
        registerLeafType(ModIds.ID_AVARITIA_CRAFTING);
        assertNull(AltarBindingRegistry.normalizeSubType(
                "extreme", ModType.byId(ModIds.ID_AVARITIA_CRAFTING)));
        assertNull(AltarBindingRegistry.normalizeSubType(
                "kjs", ModType.byId(ModIds.ID_AVARITIA_CRAFTING)));
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
    void mythicBotanyRecipeFoldersDoNotRestrictManaInfuserBindings() {
        registerLeafType("mythicbotany_mana_infuser");
        ModType infuser = ModType.byId("mythicbotany_mana_infuser");
        ResourceLocation nativeRecipe = new ResourceLocation(
                "mythicbotany", "mythicbotany_infusion/terrasteel_ingot");

        assertEquals("mythicbotany_infusion",
                AltarBindingRegistry.recipeSubTypeHint(nativeRecipe));
        assertNull(AltarBindingRegistry.normalizeSubType(
                AltarBindingRegistry.recipeSubTypeHint(nativeRecipe), infuser));
        assertNull(AltarBindingRegistry.normalizeSubType("custom_pack_folder", infuser));
        registerLeafType("test_machine_subtypes");
        assertEquals("mythicbotany_infusion",
                AltarBindingRegistry.normalizeSubType(
                        "mythicbotany_infusion", ModType.byId("test_machine_subtypes")));
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
    void wizardCrystalInfusionUsesCrystalRitualBinding() {
        registerLeafType(ModIds.WIZARDS_REBORN);
        ModType wr = ModType.byId(ModIds.WIZARDS_REBORN);
        ResourceLocation recipeId = new ResourceLocation(
                "wizards_reborn", "crystal_infusion/apotheosis_ancient_material");
        ModType.configureJei(ModIds.WIZARDS_REBORN, null,
                new String[][]{{CrystalInfusionRecipe.class.getName(), "crystal_ritual"}}, null);

        assertEquals("crystal_ritual", AltarBindingRegistry.normalizeSubType(
                AltarBindingRegistry.recipeSubTypeHint(recipeId), wr));
        String resolved = AltarBindingRegistry.recipeMachineSubType(
                new CrystalInfusionRecipe(recipeId), wr);
        assertEquals("crystal_ritual", resolved);
        assertEquals("crystal_ritual", AltarBindingRegistry.bindingSubTypeFilter(
                resolved, wr, true));
        assertEquals("arcane_iterator", AltarBindingRegistry.bindingSubTypeFilter(
                "crystal_ritual", wr, false));
    }

    @Test
    void campfireRecipeFolderDoesNotRejectFarmersDelightSkilletBinding() {
        ResourceLocation recipeId = new ResourceLocation(
                "alexsmobsdelight", "campfire_cooking/cooked_moose_rib_piece");

        assertEquals("campfire_cooking", AltarBindingRegistry.recipeSubTypeHint(recipeId));
        assertNull(AltarBindingRegistry.normalizeSubType(
                AltarBindingRegistry.recipeSubTypeHint(recipeId),
                ModType.byId(ModIds.ID_FD_SKILLET)));
    }

    @Test
    void salvagingRecipeFolderDoesNotRejectFarmersDelightCuttingBoardBinding() {
        ResourceLocation recipeId = new ResourceLocation(
                "farmersdelight", "salvaging/stone");

        assertEquals("salvaging", AltarBindingRegistry.recipeSubTypeHint(recipeId));
        assertNull(AltarBindingRegistry.normalizeSubType(
                AltarBindingRegistry.recipeSubTypeHint(recipeId),
                ModType.byId(ModIds.ID_FD_CUTTING_BOARD)));
    }

    @Test
    void addonRecipeFolderDoesNotRejectFarmersDelightCookingPotBinding() {
        ResourceLocation recipeId = new ResourceLocation(
                "cosmopolitan", "farmersdelight/cooking/tisane");

        assertEquals("farmersdelight", AltarBindingRegistry.recipeSubTypeHint(recipeId));
        assertNull(AltarBindingRegistry.normalizeSubType(
                AltarBindingRegistry.recipeSubTypeHint(recipeId),
                ModType.byId("farmersdelight_cooking_pot")));
    }

    @Test
    void addonRecipeFolderDoesNotRejectMinersDelightCopperPotBinding() {
        ResourceLocation recipeId = new ResourceLocation(
                "veggiesdelight", "compat/culturaldelight/cooking/turnip_cake");

        assertEquals("compat", AltarBindingRegistry.recipeSubTypeHint(recipeId));
        assertNull(AltarBindingRegistry.normalizeSubType(
                AltarBindingRegistry.recipeSubTypeHint(recipeId),
                ModType.byId(ModIds.ID_MD_COPPER_POT)));
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

    @Test
    void altarRecipeFoldersDoNotOverrideTheirOnlyMachineType() {
        for (String id : List.of(ModIds.TOUHOU_LITTLE_MAID, ModIds.ID_CTHULHU_FLESH_ALTAR)) {
            registerLeafType(id);
            ModType type = ModType.byId(id);
            assertNull(AltarBindingRegistry.normalizeSubType("altar", type));
            assertNull(AltarBindingRegistry.normalizeSubType("custom_recipes", type));
            assertNull(AltarBindingRegistry.recipeMachineSubType(new BiomancyFolderRecipe(
                    new ResourceLocation("test", "custom_recipes/altar_output")), type));
        }
    }

    private record BiomancyFolderRecipe(ResourceLocation id) implements Recipe<Container> {
        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public ItemStack assemble(Container container, RegistryAccess access) { return ItemStack.EMPTY; }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public ItemStack getResultItem(RegistryAccess access) { return ItemStack.EMPTY; }
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
        @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    }

    private static final class CrystalInfusionRecipe implements Recipe<Container> {
        private final ResourceLocation id;

        private CrystalInfusionRecipe(ResourceLocation id) {
            this.id = id;
        }

        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public ItemStack assemble(Container container, RegistryAccess access) { return ItemStack.EMPTY; }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public ItemStack getResultItem(RegistryAccess access) { return ItemStack.EMPTY; }
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
        @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    }
}
