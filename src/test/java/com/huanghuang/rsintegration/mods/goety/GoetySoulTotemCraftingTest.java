package com.huanghuang.rsintegration.mods.goety;

import com.Polarice3.Goety.common.items.magic.FullSpentTotem;
import com.Polarice3.Goety.common.crafting.RitualRecipe;
import net.minecraftforge.fml.ModList;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class GoetySoulTotemCraftingTest {
    @Test
    void recognizesTotemOfRootsThroughGoetysSoulTotemBaseClass() {
        assertTrue(GoetySoulTotemCrafting.isSoulTotemType(FullSpentTotem.class));
        assertFalse(GoetySoulTotemCrafting.isSoulTotemType(Object.class));
    }

    @Test
    void multipliesPerSecondSoulCostByRitualDurationWithoutOverflow() {
        assertEquals(600, GoetySoulTotemCrafting.saturatingMultiply(3, 200));
        assertEquals(Integer.MAX_VALUE,
                GoetySoulTotemCrafting.saturatingMultiply(Integer.MAX_VALUE, 2));
    }

    @Test
    void ordinaryRitualStillAccumulatesPerSecondCostWithPatchLoaded() {
        ModList mods = mock(ModList.class);
        when(mods.isLoaded("ageofmythology")).thenReturn(true);
        try (MockedStatic<ModList> loading = mockStatic(ModList.class)) {
            loading.when(ModList::get).thenReturn(mods);
            assertEquals(24_000, GoetySoulTotemCrafting.ritualSoulCost(
                    new RitualRecipe(120, 200, false)));
        }
    }

    @Test
    void managedTarotRitualUsesTotalCostWithoutMultiplyingDuration() {
        ModList mods = mock(ModList.class);
        when(mods.isLoaded("ageofmythology")).thenReturn(true);
        try (MockedStatic<ModList> loading = mockStatic(ModList.class)) {
            loading.when(ModList::get).thenReturn(mods);
            assertEquals(120, GoetySoulTotemCrafting.ritualSoulCost(
                    new RitualRecipe(120, 200, true)));
            assertEquals(120, GoetySoulTotemCrafting.ritualSoulCost(
                    new AddonRitualRecipe(120, 200)));
        }
    }

    @Test
    void missingPatchKeepsNativeCostCalculation() {
        ModList mods = mock(ModList.class);
        try (MockedStatic<ModList> loading = mockStatic(ModList.class)) {
            loading.when(ModList::get).thenReturn(mods);
            assertEquals(24_000, GoetySoulTotemCrafting.ritualSoulCost(
                    new RitualRecipe(120, 200, true)));
            assertEquals(Integer.MAX_VALUE, GoetySoulTotemCrafting.ritualSoulCost(
                    new RitualRecipe(Integer.MAX_VALUE, 2, false)));
            assertEquals(0, GoetySoulTotemCrafting.ritualSoulCost(null));
            assertEquals(0, GoetySoulTotemCrafting.ritualSoulCost(new Object()));
        }
    }

    public static final class AddonRitualRecipe extends RitualRecipe {
        public AddonRitualRecipe(int soulCost, int duration) {
            super(soulCost, duration, true);
        }
    }
}
