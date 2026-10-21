package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskManager;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MagicCraftingEnergyTaskTest extends BootstrapTest {
    @Test
    void reservesDiskGapAndInjectsItIntoNativeMachine() {
        MinecraftServer server = mock(MinecraftServer.class);
        MagicResourceReservations.Pool pool = mock(MagicResourceReservations.Pool.class);
        when(pool.remaining()).thenReturn(60L);
        when(pool.withdraw(60)).thenReturn(60L);
        CounterTarget target = new CounterTarget(40, 120);

        try (MockedStatic<MagicResourceEndpoint> endpoint = mockStatic(MagicResourceEndpoint.class)) {
            endpoint.when(() -> MagicResourceEndpoint.reserve(
                    server, MagicResourceKind.SOUL, 100)).thenReturn(pool);

            MagicCraftingEnergyTask task = MagicCraftingEnergyTask.reserve(
                    server, MagicResourceKind.SOUL, 100);
            assertTrue(task.tryPrepare(target));
            assertEquals(60, task.injected());
            assertEquals(100, target.current());
            task.complete();
        }
        verify(pool).close();
    }

    @Test
    void rejectsBudgetWhenDiskAndNativeBalanceCannotCoverIt() {
        MinecraftServer server = mock(MinecraftServer.class);
        MagicResourceReservations.Pool pool = mock(MagicResourceReservations.Pool.class);
        when(pool.remaining()).thenReturn(30L);
        CounterTarget target = new CounterTarget(20, 120);

        try (MockedStatic<MagicResourceEndpoint> endpoint = mockStatic(MagicResourceEndpoint.class)) {
            endpoint.when(() -> MagicResourceEndpoint.reserve(
                    server, MagicResourceKind.SOUL, 100)).thenReturn(pool);
            MagicCraftingEnergyTask task = MagicCraftingEnergyTask.reserve(
                    server, MagicResourceKind.SOUL, 100);
            assertFalse(task.tryPrepare(target));
            assertEquals(0, task.injected());
            task.close();
        }
    }

    @Test
    void sharedTaskReservationOwnsDiskLifetimeAndNodeConsumption() {
        MinecraftServer server = mock(MinecraftServer.class);
        MagicResourceTaskReservation taskReservation =
                mock(MagicResourceTaskReservation.class);
        MagicResourceNetworkPool pool = mock(MagicResourceNetworkPool.class);
        UUID injectionId = UUID.randomUUID();
        when(taskReservation.pool(MagicResourceKind.SOUL)).thenReturn(pool);
        when(taskReservation.recordInjection(
                MagicResourceKind.SOUL, null, 60)).thenReturn(injectionId);
        when(pool.remaining()).thenReturn(60L);
        when(pool.withdraw(60)).thenReturn(60L);

        MagicCraftingEnergyTask task = MagicCraftingEnergyTask.reserve(
                server, MagicResourceKind.SOUL, 100, taskReservation);
        assertEquals(true, task.tryPrepare(new CounterTarget(40, 120)));
        task.complete();

        verify(pool).withdraw(60);
        verify(pool, never()).close();
        verify(taskReservation).injectedConsumed(injectionId);
    }

    @Test
    void sharedTaskReservationOnlyWithdrawsTheCurrentNodeGap() {
        MinecraftServer server = mock(MinecraftServer.class);
        MagicResourceTaskReservation taskReservation =
                mock(MagicResourceTaskReservation.class);
        MagicResourceNetworkPool pool = mock(MagicResourceNetworkPool.class);
        when(taskReservation.pool(MagicResourceKind.SOUL)).thenReturn(pool);
        when(pool.remaining()).thenReturn(1_000L);
        when(pool.withdraw(60)).thenReturn(60L);

        MagicCraftingEnergyTask task = MagicCraftingEnergyTask.reserve(
                server, MagicResourceKind.SOUL, 100, taskReservation);
        assertTrue(task.tryPrepare(new CounterTarget(40, 120)));
        assertEquals(60, task.injected());
        task.complete();

        verify(pool).withdraw(60);
    }

    @Test
    void manaCanReserveRsiRecursiveEnergyButSourceUsesItsNativePath() {
        MinecraftServer server = mock(MinecraftServer.class);
        assertNotNull(MagicCraftingEnergyTask.reserve(server, MagicResourceKind.MANA, 100));
        assertNull(MagicCraftingEnergyTask.reserve(server, MagicResourceKind.SOURCE, 100));
    }

    @Test
    void logicalConsumptionKeepsBudgetsAboveInt() {
        MinecraftServer server = mock(MinecraftServer.class);
        MagicResourceReservations.Pool pool = mock(MagicResourceReservations.Pool.class);
        long total = Integer.MAX_VALUE + 123L;
        when(pool.remaining()).thenReturn(total);
        when(pool.withdraw(total)).thenReturn(total);

        try (MockedStatic<MagicResourceEndpoint> endpoint = mockStatic(MagicResourceEndpoint.class)) {
            endpoint.when(() -> MagicResourceEndpoint.reserve(
                    server, MagicResourceKind.MANA, total)).thenReturn(pool);
            MagicCraftingEnergyTask task = MagicCraftingEnergyTask.reserve(
                    server, MagicResourceKind.MANA, total);
            assertTrue(task.tryConsume());
            task.complete();
        }
        verify(pool).withdraw(total);
    }

    @Test
    void genericExtractionLeavesOtherTaskReservationsUntouched() {
        UnifiedDiskManager manager = mock(UnifiedDiskManager.class);
        UnifiedDiskCore disk = new UnifiedDiskCore(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new UnifiedDiskCore.Limits(8, 8, 1048576, 268435456));
        assertEquals(100, disk.insertMagic(MagicResourceKind.MANA, 100, true));

        try (MockedStatic<MagicDiskStore> store = mockStatic(MagicDiskStore.class)) {
            store.when(() -> MagicDiskStore.mountedDisks(manager))
                    .thenReturn(List.of(disk));
            store.when(() -> MagicDiskStore.capacity(List.of(disk), MagicResourceKind.MANA))
                    .thenReturn(MagicResourceKind.MANA.capacity());
            MagicResourceReservations.Pool pool =
                    MagicResourceReservations.reserve(manager, MagicResourceKind.MANA, 60);
            assertEquals(40, MagicResourceReservations.extract(
                    manager, MagicResourceKind.MANA, 100, false));
            assertEquals(100, disk.magicAmount(MagicResourceKind.MANA));
            pool.close();
        }
    }

    private static final class CounterTarget implements MagicCraftingEnergyTask.Target {
        private int amount;
        private final int capacity;

        private CounterTarget(int amount, int capacity) {
            this.amount = amount;
            this.capacity = capacity;
        }

        @Override
        public int current() {
            return amount;
        }

        @Override
        public int accept(int requested) {
            int accepted = Math.min(Math.max(0, requested), capacity - amount);
            amount += accepted;
            return accepted;
        }

        @Override
        public int extract(int requested) {
            int extracted = Math.min(Math.max(0, requested), amount);
            amount -= extracted;
            return extracted;
        }
    }
}
