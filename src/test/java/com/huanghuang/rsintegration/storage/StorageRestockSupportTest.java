package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class StorageRestockSupportTest extends BootstrapTest {
    @Test
    void onlyConfirmedZeroStockEnablesFallback() {
        var endpoint = mock(CraftStorageEndpoint.class);
        var player = mock(ServerPlayer.class);
        var template = new ItemStack(Items.APPLE);
        var mode = StorageOperationMode.PERFORM;
        var key = StorageItemKey.fromItemStack(new StorageBackendId("test"), template);
        var session = mock(StorageSession.class);
        when(endpoint.session()).thenReturn(session);
        when(session.itemKey(any())).thenReturn(key);
        when(endpoint.extractExact(player, template, 1, false)).thenReturn(
                StorageOperationResult.extracted(mode, 1, List.of()),
                StorageOperationResult.failedExtraction(mode, 1, StorageOperationStatus.DENIED, List.of(), List.of()),
                StorageOperationResult.indeterminateExtraction(1, List.of(template), List.of(),
                        StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION));
        var missing = StorageRestockSupport.extractOutcome(endpoint, player, template, 1);
        assertTrue(missing.confirmedMissing());
        assertFalse(missing.failed());
        var denied = StorageRestockSupport.extractOutcome(endpoint, player, template, 1);
        assertFalse(denied.confirmedMissing());
        assertTrue(denied.failed());
        var uncertain = StorageRestockSupport.extractOutcome(endpoint, player, template, 1);
        assertFalse(uncertain.confirmedMissing());
        assertTrue(uncertain.failed());
        assertTrue(uncertain.stack().is(Items.APPLE));
    }

    @Test
    void exactFragmentsAreMergedWithoutChangingTheirIdentity() {
        ItemStack template = new ItemStack(Items.LAPIS_LAZULI);

        ItemStack merged = StorageRestockSupport.mergeExactStacks(template, List.of(
                new ItemStack(Items.LAPIS_LAZULI, 20),
                new ItemStack(Items.LAPIS_LAZULI, 12)),
                ItemStack::isSameItemSameTags);

        assertEquals(32, merged.getCount());
        assertTrue(ItemStack.isSameItemSameTags(template, merged));
    }

    @Test
    void unexpectedExtractionIdentityRejectsTheWholeResult() {
        ItemStack merged = StorageRestockSupport.mergeExactStacks(
                new ItemStack(Items.LAPIS_LAZULI), List.of(
                        new ItemStack(Items.LAPIS_LAZULI, 3),
                        new ItemStack(Items.DIAMOND, 1)),
                ItemStack::isSameItemSameTags);

        assertTrue(merged.isEmpty());
    }
}
