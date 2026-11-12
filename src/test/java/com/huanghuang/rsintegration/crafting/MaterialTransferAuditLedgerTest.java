package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridFluidRecovery;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

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

        assertTrue(loaded.resolve(serverWithRecoveryStorage(Map.of()), attemptId,
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
        MinecraftServer server = serverWithRecoveryStorage(Map.of());
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
        MinecraftServer server = serverWithRecoveryStorage(Map.of());
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
        MinecraftServer server = serverWithRecoveryStorage(Map.of());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savedRecoveryAndOlderAuditCannotChangeRefundAmountOrMarkConsumed(boolean fluid) {
        MinecraftServer server = serverWithRecoveryStorage(Map.of());
        UUID owner = UUID.randomUUID();
        UUID resolver = UUID.randomUUID();
        MaterialTransferAuditLedger original = new MaterialTransferAuditLedger();
        UUID id = record(original, owner, fluid, 10L);
        CompoundTag olderTag = original.save(new CompoundTag());
        assertTrue(original.resolve(server, id, MaterialTransferAuditLedger.Resolution.REFUND, 3L, resolver, 2L));
        MinecraftServer restarted = serverWithRecoveryStorage(saveRecoveries(server));
        MaterialTransferAuditLedger older = new MaterialTransferAuditLedger(olderTag);

        assertFalse(older.resolve(restarted, id, MaterialTransferAuditLedger.Resolution.REFUND, 7L, null, 3L));
        assertFalse(older.resolve(restarted, id, MaterialTransferAuditLedger.Resolution.CONSUMED, 0L, null, 3L));
        assertEquals(3L, recoveryAmount(restarted, fluid));
        older.replayRefunds(restarted);
        older.replayRefunds(restarted);
        assertEquals(3L, older.resolvedAttempt(id).refundAmount());
        assertEquals(resolver, older.resolvedAttempt(id).resolvedBy());
        assertEquals(2L, older.resolvedAttempt(id).resolvedAt());
        assertEquals(3L, recoveryAmount(restarted, fluid));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryRestoresMissingAuditAndDoesNotReissueAlreadyClaimedParts(boolean fluid) {
        MinecraftServer server = serverWithRecoveryStorage(Map.of());
        MaterialTransferAuditLedger original = new MaterialTransferAuditLedger();
        UUID id = record(original, UUID.randomUUID(), fluid, 10L);
        assertTrue(original.resolve(server, id, MaterialTransferAuditLedger.Resolution.REFUND, 3L, null, 2L));
        Map<String, CompoundTag> tags = saveRecoveries(server);
        // 模拟队列已经领取并存盘，但整个审核档案尚未落盘。
        tags.values().forEach(tag -> tag.remove("pending"));
        MinecraftServer restarted = serverWithRecoveryStorage(tags);
        MaterialTransferAuditLedger missing = new MaterialTransferAuditLedger();
        missing.replayRefunds(restarted);
        missing.replayRefunds(restarted);
        assertEquals(3L, missing.resolvedAttempt(id).refundAmount());
        assertEquals(0L, recoveryAmount(restarted, fluid));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditAheadOfRecoveryRepairsBothResourceTypes(boolean fluid) {
        MinecraftServer originalServer = serverWithRecoveryStorage(Map.of());
        MaterialTransferAuditLedger original = new MaterialTransferAuditLedger();
        UUID id = record(original, UUID.randomUUID(), fluid, 10L);
        assertTrue(original.resolve(originalServer, id, MaterialTransferAuditLedger.Resolution.REFUND, 4L, null, 2L));
        MaterialTransferAuditLedger loaded = new MaterialTransferAuditLedger(original.save(new CompoundTag()));
        MinecraftServer missingRecovery = serverWithRecoveryStorage(Map.of());
        loaded.replayRefunds(missingRecovery);
        loaded.replayRefunds(missingRecovery);
        assertEquals(4L, recoveryAmount(missingRecovery, fluid));
        assertEquals(4L, loaded.resolvedAttempt(id).refundAmount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyRefundWithoutDecisionBlocksGuessingAndConsumedResolution(boolean fluid) {
        MinecraftServer server = serverWithRecoveryStorage(Map.of());
        MaterialTransferAuditLedger ledger = new MaterialTransferAuditLedger();
        UUID owner = UUID.randomUUID();
        UUID id = record(ledger, owner, fluid, 10L);
        UUID partId = MaterialTransferAuditLedger.RefundJournal.partId(id, 0);
        if (fluid) {
            UnifiedGridFluidRecovery.get(server).retainRawOnce(owner,
                    new FluidStack(Fluids.WATER, 3).writeToNBT(new CompoundTag()), null, partId);
        } else {
            CraftItemRecovery.get(server).retainOnce(owner, new ItemStack(Items.IRON_INGOT, 3), partId);
        }
        MinecraftServer restarted = serverWithRecoveryStorage(saveRecoveries(server));
        assertFalse(ledger.resolve(restarted, id, MaterialTransferAuditLedger.Resolution.REFUND, 7L, null, 2L));
        assertFalse(ledger.resolve(restarted, id, MaterialTransferAuditLedger.Resolution.CONSUMED, 0L, null, 2L));
        assertNotNull(ledger.pendingAttempt(id));
        assertEquals(3L, recoveryAmount(restarted, fluid));
    }

    @Test
    void stableTransferIdStaysDeduplicatedAfterResolutionAndReload() {
        MaterialTransferAuditLedger ledger = new MaterialTransferAuditLedger();
        UUID id = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ledger.recordUnknown(id, task, owner, MaterialTransferAuditLedger.ResourceType.UNKNOWN,
                null, null, 10L, "首次通知", 1L);
        ledger.recordUnknown(id, task, owner, MaterialTransferAuditLedger.ResourceType.UNKNOWN,
                null, null, 10L, "再次通知", 2L);
        assertEquals(1, ledger.pendingAttempts().size());
        assertTrue(ledger.resolve(serverWithRecoveryStorage(Map.of()), id,
                MaterialTransferAuditLedger.Resolution.CONSUMED, 0L, null, 3L));
        MaterialTransferAuditLedger loaded = new MaterialTransferAuditLedger(ledger.save(new CompoundTag()));
        loaded.recordUnknown(id, task, owner, MaterialTransferAuditLedger.ResourceType.UNKNOWN,
                null, null, 10L, "重启后通知", 4L);
        assertTrue(loaded.pendingAttempts().isEmpty());
        assertEquals(1, loaded.resolvedAttempts().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partiallySavedRecoveryResumesOriginalDecisionWithOlderAudit(boolean fluid) {
        MinecraftServer server = serverWithRecoveryStorage(Map.of());
        MaterialTransferAuditLedger ledger = new MaterialTransferAuditLedger();
        long amount = (long) Integer.MAX_VALUE + 8L;
        UUID resolver = UUID.randomUUID();
        UUID id = record(ledger, UUID.randomUUID(), fluid, amount);
        CompoundTag olderAudit = ledger.save(new CompoundTag());
        CraftItemRecovery items = spy(CraftItemRecovery.get(server));
        UnifiedGridFluidRecovery fluids = spy(UnifiedGridFluidRecovery.get(server));
        AtomicInteger parts = new AtomicInteger();
        doAnswer(call -> {
            if (parts.incrementAndGet() == 2) throw new IllegalStateException("第二片写入失败");
            return call.callRealMethod();
        }).when(items).retainOnce(any(), any(), any());
        doAnswer(call -> {
            if (parts.incrementAndGet() == 2) throw new IllegalStateException("第二片写入失败");
            return call.callRealMethod();
        }).when(fluids).retainRawOnce(any(), any(), any(), any());
        Map<String, CompoundTag> saved;
        try (var itemRecovery = mockStatic(CraftItemRecovery.class);
             var fluidRecovery = mockStatic(UnifiedGridFluidRecovery.class)) {
            itemRecovery.when(() -> CraftItemRecovery.get(server)).thenReturn(items);
            fluidRecovery.when(() -> UnifiedGridFluidRecovery.get(server)).thenReturn(fluids);
            assertFalse(ledger.resolve(server, id, MaterialTransferAuditLedger.Resolution.REFUND, amount, resolver, 2L));
            assertEquals(Integer.MAX_VALUE, recoveryAmount(server, fluid));
            saved = saveRecoveries(server);
        }
        MinecraftServer restarted = serverWithRecoveryStorage(saved);
        MaterialTransferAuditLedger loaded = new MaterialTransferAuditLedger(olderAudit);
        assertFalse(loaded.resolve(restarted, id, MaterialTransferAuditLedger.Resolution.REFUND, amount - 1, null, 3L));
        loaded.replayRefunds(restarted);
        loaded.replayRefunds(restarted);
        assertEquals(amount, recoveryAmount(restarted, fluid));
        assertEquals(amount, loaded.resolvedAttempt(id).refundAmount());
        assertEquals(resolver, loaded.resolvedAttempt(id).resolvedBy());
        assertEquals(2L, loaded.resolvedAttempt(id).resolvedAt());
    }

    private static UUID record(MaterialTransferAuditLedger ledger, UUID owner, boolean fluid, long amount) {
        return fluid ? ledger.recordUnknownFluid(UUID.randomUUID(), owner, new FluidStack(Fluids.WATER, 1),
                amount, "未知流体", 1L) : ledger.recordUnknownItem(UUID.randomUUID(), owner,
                new ItemStack(Items.IRON_INGOT), amount, "未知物品", 1L);
    }

    private static long recoveryAmount(MinecraftServer server, boolean fluid) {
        CompoundTag tag = fluid ? UnifiedGridFluidRecovery.get(server).save(new CompoundTag())
                : CraftItemRecovery.get(server).save(new CompoundTag());
        return tag.getList("pending", Tag.TAG_COMPOUND).stream().mapToLong(value -> fluid
                ? ((CompoundTag) value).getCompound("fluid").getInt("Amount")
                : ((CompoundTag) value).getInt("amount")).sum();
    }

    private static Map<String, CompoundTag> saveRecoveries(MinecraftServer server) {
        return Map.of("rs_integration_craft_item_recovery", CraftItemRecovery.get(server).save(new CompoundTag()),
                "rs_integration_unified_grid_fluid_recovery", UnifiedGridFluidRecovery.get(server).save(new CompoundTag()));
    }

    private static MinecraftServer serverWithRecoveryStorage(Map<String, CompoundTag> saved) {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        DimensionDataStorage storage = mock(DimensionDataStorage.class);
        Map<String, SavedData> loaded = new HashMap<>();
        when(server.overworld()).thenReturn(level);
        when(level.getDataStorage()).thenReturn(storage);
        when(storage.computeIfAbsent(any(), any(), anyString())).thenAnswer(call -> {
            Function<CompoundTag, SavedData> reader = call.getArgument(0);
            Supplier<SavedData> factory = call.getArgument(1);
            String name = call.getArgument(2);
            return loaded.computeIfAbsent(name, ignored -> saved.containsKey(name)
                    ? reader.apply(saved.get(name).copy()) : factory.get());
        });
        return server;
    }
}
