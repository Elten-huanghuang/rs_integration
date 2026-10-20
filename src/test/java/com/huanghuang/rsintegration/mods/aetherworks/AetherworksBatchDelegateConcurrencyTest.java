package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AetherworksBatchDelegateConcurrencyTest extends BootstrapTest {

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
