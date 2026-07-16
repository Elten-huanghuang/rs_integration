package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EreAlchemyLockTest extends BootstrapTest {
    private static ResourceKey<Level> dimension() {
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation("test", "overworld"));
    }
    private static final BlockPos POS = new BlockPos(10, 64, 10);

    @Test
    void ownerLeaseCannotBeReleasedByAnotherOwner() {
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        EreAlchemyLock.Lease lease = EreAlchemyLock.tryAcquire(dimension(), POS, firstOwner);
        assertNotNull(lease);
        assertNull(EreAlchemyLock.tryAcquire(dimension(), POS, secondOwner));
        assertFalse(EreAlchemyLock.release(new EreAlchemyLock.Lease(lease.position(), secondOwner, lease.generation())));
        assertTrue(EreAlchemyLock.release(lease));
        assertFalse(EreAlchemyLock.release(lease));
    }

    @Test
    void equivalentOwnerValueCanReleaseLease() {
        UUID owner = UUID.randomUUID();
        EreAlchemyLock.Lease lease = EreAlchemyLock.tryAcquire(dimension(), POS, owner);
        assertNotNull(lease);

        UUID equivalentOwner = UUID.fromString(owner.toString());
        assertTrue(EreAlchemyLock.release(new EreAlchemyLock.Lease(
                lease.position(), equivalentOwner, lease.generation())));
        assertNotNull(EreAlchemyLock.tryAcquire(dimension(), POS, UUID.randomUUID()));
        EreAlchemyLock.clearAll();
    }
}
