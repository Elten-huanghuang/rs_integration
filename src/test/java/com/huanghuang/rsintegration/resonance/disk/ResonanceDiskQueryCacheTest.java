package com.huanghuang.rsintegration.resonance.disk;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ResonanceDiskQueryCacheTest extends BootstrapTest {
    @Test
    void reusesSnapshotWithinOneContentRevision() {
        IStorageDisk<ItemStack> delegate = mock(IStorageDisk.class);
        ItemStack diamond = new ItemStack(Items.DIAMOND, 3);
        when(delegate.getStacks()).thenReturn(List.of(diamond));

        ResonanceDiskWrapper disk = new ResonanceDiskWrapper(delegate);

        assertTrue(disk.hasItem(stack -> stack.is(Items.DIAMOND)));
        assertEquals(3, disk.countItems(stack -> stack.is(Items.DIAMOND)));
        verify(delegate, times(1)).getStacks();
    }

    @Test
    void invalidatesSnapshotAfterARealMutation() {
        IStorageDisk<ItemStack> delegate = mock(IStorageDisk.class);
        ItemStack diamond = new ItemStack(Items.DIAMOND, 3);
        ItemStack emerald = new ItemStack(Items.EMERALD, 2);
        AtomicReference<List<ItemStack>> stacks = new AtomicReference<>(List.of(diamond));
        when(delegate.getStacks()).thenAnswer(invocation -> stacks.get());
        when(delegate.getStored()).thenReturn(1, 2);
        when(delegate.insert(any(ItemStack.class), anyInt(), eq(Action.PERFORM)))
                .thenAnswer(invocation -> {
                    stacks.set(List.of(emerald));
                    return ItemStack.EMPTY;
                });

        ResonanceDiskWrapper disk = new ResonanceDiskWrapper(delegate);
        assertTrue(disk.hasItem(stack -> stack.is(Items.DIAMOND)));

        disk.manualInsertUnassigned(new ItemStack(Items.EMERALD), 1, Action.PERFORM);

        assertTrue(disk.hasItem(stack -> stack.is(Items.EMERALD)));
        assertEquals(2, disk.countItems(stack -> stack.is(Items.EMERALD)));
        verify(delegate, times(2)).getStacks();
    }

    @Test
    void predicatesAndPublicSnapshotsCannotPoisonCachedStacks() {
        IStorageDisk<ItemStack> delegate = mock(IStorageDisk.class);
        ItemStack diamond = new ItemStack(Items.DIAMOND, 3);
        diamond.getOrCreateTag().putInt("RSISlot", 7);
        diamond.getOrCreateTag().putString("variant", "original");
        when(delegate.getStacks()).thenReturn(List.of(diamond));
        ResonanceDiskWrapper disk = new ResonanceDiskWrapper(delegate);

        assertTrue(disk.hasItem(stack -> {
            assertFalse(stack.getTag().contains("RSISlot"));
            stack.setCount(1);
            stack.getOrCreateTag().putString("variant", "changed");
            return true;
        }));
        ItemStack publicSnapshot = disk.storedStacks().get(0).stack();
        publicSnapshot.setCount(1);
        publicSnapshot.getOrCreateTag().putString("variant", "public_change");

        assertEquals(3, disk.countItems(stack ->
                "original".equals(stack.getTag().getString("variant"))));
        assertEquals(3, diamond.getCount());
        assertEquals(7, diamond.getTag().getInt("RSISlot"));
        assertEquals("original", diamond.getTag().getString("variant"));
    }

    @Test
    void simulatedAndRejectedInsertsKeepTheExistingSnapshot() {
        IStorageDisk<ItemStack> delegate = mock(IStorageDisk.class);
        when(delegate.getStacks()).thenReturn(List.of(new ItemStack(Items.DIAMOND, 3)));
        when(delegate.getStored()).thenReturn(3);
        when(delegate.insert(any(ItemStack.class), anyInt(), any(Action.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ResonanceDiskWrapper disk = new ResonanceDiskWrapper(delegate);
        assertTrue(disk.hasItem(stack -> stack.is(Items.DIAMOND)));

        disk.manualInsert(1, new ItemStack(Items.EMERALD), 1, Action.SIMULATE);
        disk.manualInsert(1, new ItemStack(Items.EMERALD), 1, Action.PERFORM);

        assertEquals(0, disk.contentRevision());
        assertEquals(3, disk.countItems(stack -> stack.is(Items.DIAMOND)));
        verify(delegate, times(1)).getStacks();
    }

    @Test
    void cachesEmptyDiskAndStopsPredicateEvaluationAtTheFirstMatch() {
        IStorageDisk<ItemStack> emptyDelegate = mock(IStorageDisk.class);
        when(emptyDelegate.getStacks()).thenReturn(List.of());
        ResonanceDiskWrapper empty = new ResonanceDiskWrapper(emptyDelegate);
        assertFalse(empty.hasItem(stack -> true));
        assertEquals(0, empty.countItems(stack -> true));
        verify(emptyDelegate, times(1)).getStacks();

        IStorageDisk<ItemStack> delegate = mock(IStorageDisk.class);
        when(delegate.getStacks()).thenReturn(List.of(
                new ItemStack(Items.DIAMOND), new ItemStack(Items.EMERALD)));
        ResonanceDiskWrapper disk = new ResonanceDiskWrapper(delegate);
        assertTrue(disk.hasItem(stack -> {
            assertTrue(stack.is(Items.DIAMOND));
            return true;
        }));
    }
}
