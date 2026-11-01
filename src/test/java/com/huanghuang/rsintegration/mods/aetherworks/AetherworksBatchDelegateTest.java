package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.sirplop.aetherworks.blockentity.AetheriumAnvilBlockEntity;
import net.sirplop.aetherworks.blockentity.IForge;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AetherworksBatchDelegateTest extends BootstrapTest {

    @Test
    void refreshesNativeForgeStateBeforeReadingAnvilEnergyFlag() {
        AetheriumAnvilBlockEntity anvil = mock(AetheriumAnvilBlockEntity.class);
        IForge forge = mock(IForge.class);

        AetherworksBatchDelegate.syncNativeForgeState(anvil, forge);

        verify(anvil).onForgeTick(forge);
    }

    @Test
    void eachAnvilOwnsItsOperationLeaseEvenWhenForgeIsShared() throws Exception {
        AetherworksBatchDelegate delegate = new AetherworksBatchDelegate();
        BlockPos anvilPos = new BlockPos(12, 64, 18);
        Field machinePos = AetherworksBatchDelegate.class.getDeclaredField("machinePos");
        machinePos.setAccessible(true);
        machinePos.set(delegate, anvilPos);
        Field forgePos = AetherworksBatchDelegate.class.getDeclaredField("forgePos");
        forgePos.setAccessible(true);
        forgePos.set(delegate, new BlockPos(8, 64, 16));

        assertEquals(anvilPos, delegate.getOperationMachinePos(anvilPos));
    }
}
