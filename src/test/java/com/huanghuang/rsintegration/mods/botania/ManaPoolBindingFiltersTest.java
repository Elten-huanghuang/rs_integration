package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;
import vazkii.botania.api.recipe.ManaInfusionRecipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManaPoolBindingFiltersTest extends BootstrapTest {
    @Test
    void directPoolAndCatalystBindingsShareTheSameMachineFamily() {
        String pool = "mana_pool||block.rs_integration.resonant_mana_pool";
        String alchemy = "alchemy_catalyst||block.botania.alchemy_catalyst";
        String conjuration = "conjuration_catalyst||block.botania.conjuration_catalyst";

        for (String filter : new String[]{
                ManaPoolBindingFilters.MANA_POOL,
                ManaPoolBindingFilters.ALCHEMY_CATALYST,
                ManaPoolBindingFilters.CONJURATION_CATALYST}) {
            assertTrue(ManaPoolBindingFilters.matches(pool, filter));
            assertTrue(ManaPoolBindingFilters.matches(alchemy, filter));
            assertTrue(ManaPoolBindingFilters.matches(conjuration, filter));
        }
        assertFalse(ManaPoolBindingFilters.matches("block.minecraft.furnace", "mana_pool"));
    }

    @Test
    void plainManaInfusionRecipeRoutesThroughThePoolMachine() {
        ManaInfusionRecipe recipe = mock(ManaInfusionRecipe.class);
        when(recipe.getRecipeCatalyst()).thenReturn(null);

        assertEquals(ManaPoolBindingFilters.MANA_POOL,
                ManaPoolBindingFilters.filterForRecipe(recipe));
        assertNull(ManaPoolBindingFilters.filterForRecipe(new Object()));
    }
}
