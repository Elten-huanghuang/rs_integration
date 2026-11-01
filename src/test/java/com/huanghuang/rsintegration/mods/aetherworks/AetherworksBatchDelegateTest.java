package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.sirplop.aetherworks.blockentity.AetheriumAnvilBlockEntity;
import net.sirplop.aetherworks.blockentity.IForge;
import org.junit.jupiter.api.Test;

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
}
