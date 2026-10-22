package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.sirplop.aetherworks.blockentity.ToolStationBlockEntity;
import net.sirplop.aetherworks.recipe.IToolStationRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AetherworksToolStationBatchDelegateTest extends BootstrapTest {

    @Test
    void recipeInitializationDoesNotConsumeAHammerHit() {
        ToolStationBlockEntity station = mock(ToolStationBlockEntity.class);
        station.hasEmber = true;
        station.hasHeat = true;

        doAnswer(invocation -> {
            assertFalse(station.hasEmber);
            assertFalse(station.hasHeat);
            station.cachedRecipe = mock(IToolStationRecipe.class);
            return false;
        }).when(station).onHit();

        assertTrue(AetherworksToolStationBatchDelegate.initializeNativeRecipe(station));
        verify(station).onHit();
        verify(station).setChanged();
    }

    @Test
    void missingNativeRecipeIsReportedToTheCaller() {
        ToolStationBlockEntity station = mock(ToolStationBlockEntity.class);
        when(station.onHit()).thenReturn(false);

        assertFalse(AetherworksToolStationBatchDelegate.initializeNativeRecipe(station));
    }

    @Test
    void directHeatingExceedsNativeStrictTemperatureThreshold() {
        assertEquals(2601.0, AetherworksToolStationBatchDelegate.directHeatTarget(2600));
    }
}
