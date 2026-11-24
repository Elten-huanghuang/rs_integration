package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemRetrievalServiceTest extends BootstrapTest {
    @Test
    void validatesBackendIdentityWithoutMergingOrRewritingPayloads() {
        var session = mock(StorageSession.class);
        var backend = new StorageBackendId("beyonddimensions");
        when(session.itemKey(any(ItemStack.class))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            ItemStack payload = stack.copyWithCount(1);
            ItemStack canonical = payload.copy();
            if (canonical.getTag() != null && canonical.getTag().isEmpty()) canonical.setTag(null);
            return StorageItemKey.withCanonicalIdentity(backend, payload.save(new CompoundTag()),
                    canonical.save(new CompoundTag()), payload);
        });
        ItemStack plain = new ItemStack(Items.IRON_INGOT, 2);
        ItemStack emptyTag = new ItemStack(Items.IRON_INGOT, 3);
        emptyTag.setTag(new CompoundTag());
        var key = session.itemKey(plain);
        var result = StorageOperationResult.extracted(StorageOperationMode.PERFORM, 5, List.of(plain, emptyTag));
        var fragments = result.extractedStacks();

        assertTrue(ItemRetrievalService.validFragments(session, key, result, 5, fragments));
        assertFalse(ItemStack.isSameItemSameTags(fragments.get(0), fragments.get(1)));
        assertNotNull(fragments.get(1).getTag());
        assertFalse(ItemRetrievalService.validFragments(session, key, result, 5, List.of(plain)));
        assertFalse(ItemRetrievalService.validFragments(session, key, result, 5,
                List.of(new ItemStack(Items.GOLD_INGOT, 5))));
    }
}
