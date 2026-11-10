package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import vazkii.botania.common.block.block_entity.RunicAltarBlockEntity;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunicAltarProgressTest extends BootstrapTest {
    @Test
    void firstWorkingObservationReportsStoredManaInsteadOfElapsedTime() throws ReflectiveOperationException {
        RunicAltarBatchDelegate delegate = new RunicAltarBatchDelegate();
        Field started = RunicAltarBatchDelegate.class.getDeclaredField("started");
        started.setAccessible(true);
        started.setBoolean(delegate, true);
        RunicAltarBlockEntity altar = mock(RunicAltarBlockEntity.class);
        altar.manaToGet = 1000;
        when(altar.getCurrentMana()).thenReturn(250);

        var observation = delegate.observeMachineCraft(mock(ServerLevel.class), altar);

        assertEquals(IBatchDelegate.CraftPhase.WORKING, observation.phase());
        assertEquals(250L, observation.completedWork());
    }

    @Test
    void altarWithoutActiveManaRequirementDoesNotReportUnrelatedStoredMana() {
        RunicAltarBatchDelegate delegate = new RunicAltarBatchDelegate();
        RunicAltarBlockEntity altar = mock(RunicAltarBlockEntity.class);
        when(altar.getCurrentMana()).thenReturn(250);
        assertEquals(0L, delegate.observeMachineCraft(mock(ServerLevel.class), altar).completedWork());
    }
}
