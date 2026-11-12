package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.any;

class MaterialTransferAuditLedgerTest extends BootstrapTest {
    @BeforeAll static void configure() { RuntimeMaterialAccountTest.configure(); }

    @Test
    void pendingFluidAuditRoundTripsAndCanBeMarkedConsumed() {
        UUID taskId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID resolverId = UUID.randomUUID();
        FluidStack fluid = new FluidStack(Fluids.WATER, 1);
        CompoundTag identity = new CompoundTag();
        identity.putString("grade", "pure");
        fluid.setTag(identity);

        MaterialTransferAuditLedger original = new MaterialTransferAuditLedger();
        UUID attemptId = original.recordUnknownFluid(taskId, ownerId, fluid, 750,
                "机器输入结果不明", 120L);
        MaterialTransferAuditLedger loaded = new MaterialTransferAuditLedger(
                original.save(new CompoundTag()));

        MaterialTransferAuditLedger.Pending attempt = loaded.pendingAttempts().get(0);
        assertEquals(attemptId, attempt.attemptId());
        assertEquals(taskId, attempt.taskId());
        assertEquals(ownerId, attempt.ownerId());
        assertEquals(MaterialTransferAuditLedger.ResourceType.FLUID, attempt.type());
        assertEquals(identity, attempt.identity());
        assertEquals(750L, attempt.amount());
        assertEquals("机器输入结果不明", attempt.reason());

        assertTrue(loaded.resolve(mock(MinecraftServer.class), attemptId,
                MaterialTransferAuditLedger.Resolution.CONSUMED, 0L, resolverId, 160L));
        assertTrue(loaded.pendingAttempts().isEmpty());
        MaterialTransferAuditLedger.Resolved resolved = loaded.resolvedAttempts().get(0);
        assertEquals(attemptId, resolved.attempt().attemptId());
        assertEquals(MaterialTransferAuditLedger.Resolution.CONSUMED, resolved.resolution());
        assertEquals(resolverId, resolved.resolvedBy());
        assertEquals(160L, resolved.resolvedAt());
    }

    @Test
    void unknownResourceCannotBeRefundedByGuessing() {
        MaterialTransferAuditLedger ledger = new MaterialTransferAuditLedger();
        UUID attemptId = ledger.recordUnknown(UUID.randomUUID(), UUID.randomUUID(),
                MaterialTransferAuditLedger.ResourceType.UNKNOWN, null,
                new CompoundTag(), 400, "资源身份不明", 1L);

        assertFalse(ledger.resolve(mock(MinecraftServer.class), attemptId,
                MaterialTransferAuditLedger.Resolution.REFUND, 400L, null, 2L));
        assertEquals(1, ledger.pendingAttempts().size());
        assertTrue(ledger.resolvedAttempts().isEmpty());
    }

    @Test
    void partialRefundKeepsDecisionAndReplaysWithoutIssuingTwice() {
        UUID owner = UUID.randomUUID();
        MinecraftServer server = mock(MinecraftServer.class);
        MaterialTransferAuditLedger ledger = new MaterialTransferAuditLedger();
        UUID attemptId = ledger.recordUnknownItem(UUID.randomUUID(), owner,
                new ItemStack(Items.IRON_INGOT), 10L, "未知提取", 5L);
        CraftItemRecovery recovery = new CraftItemRecovery();
        try (var recoveries = mockStatic(CraftItemRecovery.class)) {
            recoveries.when(() -> CraftItemRecovery.get(server)).thenReturn(recovery);
            assertFalse(ledger.resolve(server, attemptId, MaterialTransferAuditLedger.Resolution.REFUND,
                    11L, null, 6L));
            assertTrue(ledger.resolve(server, attemptId, MaterialTransferAuditLedger.Resolution.REFUND,
                    3L, null, 6L));
            assertFalse(ledger.resolve(server, attemptId, MaterialTransferAuditLedger.Resolution.REFUND,
                    3L, null, 6L));
            MaterialTransferAuditLedger loaded = new MaterialTransferAuditLedger(ledger.save(new CompoundTag()));
            loaded.replayRefunds(server);
            assertEquals(3L, loaded.resolvedAttempts().get(0).refundAmount());
            assertEquals(3, recovery.pendingStacksForTests(owner).get(0).getCount());
        }
    }

    @Test
    void persistedRefundDecisionRepairsMissingRecoveryQueue() {
        UUID owner = UUID.randomUUID();
        MinecraftServer server = mock(MinecraftServer.class);
        MaterialTransferAuditLedger ledger = new MaterialTransferAuditLedger();
        UUID attemptId = ledger.recordUnknownItem(UUID.randomUUID(), owner,
                new ItemStack(Items.IRON_INGOT), 4L, "未知退款", 1L);
        CraftItemRecovery original = new CraftItemRecovery();
        CraftItemRecovery restored = new CraftItemRecovery();
        try (var recoveries = mockStatic(CraftItemRecovery.class)) {
            recoveries.when(() -> CraftItemRecovery.get(server)).thenReturn(original);
            assertTrue(ledger.resolve(server, attemptId, MaterialTransferAuditLedger.Resolution.REFUND,
                    4L, null, 2L));
            MaterialTransferAuditLedger loaded = new MaterialTransferAuditLedger(ledger.save(new CompoundTag()));
            recoveries.when(() -> CraftItemRecovery.get(server)).thenReturn(restored);
            loaded.replayRefunds(server);
            loaded.replayRefunds(server);
            assertEquals(4, restored.pendingStacksForTests(owner).get(0).getCount());
        }
    }

    @Test
    void failedMultipartRefundLocksDecisionAndResumesAfterReloadWithoutRepeatingFirstPart() {
        UUID owner = UUID.randomUUID();
        UUID resolver = UUID.randomUUID();
        MinecraftServer server = mock(MinecraftServer.class);
        MaterialTransferAuditLedger ledger = new MaterialTransferAuditLedger();
        long amount = (long) Integer.MAX_VALUE + 7L;
        UUID attemptId = ledger.recordUnknownItem(UUID.randomUUID(), owner,
                new ItemStack(Items.IRON_INGOT), amount, "分片退款", 1L);
        CraftItemRecovery recovery = spy(new CraftItemRecovery());
        AtomicInteger parts = new AtomicInteger();
        doAnswer(call -> {
            if (parts.incrementAndGet() == 2) throw new IllegalStateException("恢复队列暂时不可用");
            return call.callRealMethod();
        }).when(recovery).retainOnce(any(), any(), any());
        try (var recoveries = mockStatic(CraftItemRecovery.class)) {
            recoveries.when(() -> CraftItemRecovery.get(server)).thenReturn(recovery);
            assertFalse(ledger.resolve(server, attemptId, MaterialTransferAuditLedger.Resolution.REFUND,
                    amount, resolver, 2L));
            assertEquals(amount, ledger.refundIntent(attemptId).refundAmount());
            assertEquals(Integer.MAX_VALUE, recovery.pendingStacksForTests(owner).get(0).getCount());

            MaterialTransferAuditLedger loaded = new MaterialTransferAuditLedger(ledger.save(new CompoundTag()));
            assertFalse(loaded.resolve(server, attemptId, MaterialTransferAuditLedger.Resolution.REFUND,
                    amount - 1, resolver, 3L));
            assertFalse(loaded.resolve(server, attemptId, MaterialTransferAuditLedger.Resolution.CONSUMED,
                    0L, resolver, 3L));
            CraftItemRecovery restored = new CraftItemRecovery(recovery.save(new CompoundTag()));
            recoveries.when(() -> CraftItemRecovery.get(server)).thenReturn(restored);
            loaded.replayRefunds(server);
            loaded.replayRefunds(server);
            assertTrue(loaded.pendingAttempts().isEmpty());
            assertEquals(amount, restored.pendingStacksForTests(owner).stream().mapToLong(ItemStack::getCount).sum());
            assertEquals(amount, loaded.resolvedAttempt(attemptId).refundAmount());
            assertEquals(resolver, loaded.resolvedAttempt(attemptId).resolvedBy());
            assertEquals(2L, loaded.resolvedAttempt(attemptId).resolvedAt());
        }
    }
}
