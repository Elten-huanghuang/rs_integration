package com.huanghuang.rsintegration.disk;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.rs.IndexedStackList;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.util.IComparer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndexedStackListTest extends BootstrapTest {
    @SuppressWarnings("unchecked") private IStorage<ItemStack> source() {
        IStorage<ItemStack> source = mock(IStorage.class); when(source.getAccessType()).thenReturn(AccessType.INSERT_EXTRACT); return source;
    }
    @Test void saturationDecreaseUsesSeparateSourceContributions() {
        var first = source(); var second = source();
        IndexedStackList<ItemStack> list = new IndexedStackList<>(FrozenKey.Kind.ITEM, () -> List.of(first, second));
        ItemStack stone = new ItemStack(Items.STONE);
        list.beginRebuild(); list.sourceForRebuild(first); list.add(stone, Integer.MAX_VALUE);
        list.sourceForRebuild(second); list.add(stone, 50); list.endRebuild();
        assertEquals(Integer.MAX_VALUE, list.getCount(stone));
        list.changed(first, stone, -Integer.MAX_VALUE);
        var result = list.remove(stone, Integer.MAX_VALUE);
        assertEquals(50, result.getStack().getCount()); assertEquals(50 - Integer.MAX_VALUE, result.getChange());
        assertEquals(50, list.getCount(stone));
        verify(first, never()).extract(any(), anyInt(), anyInt(), any());
    }

    @Test void exactVariantsIdsCopiesAndQuantityMatching() {
        IndexedStackList<ItemStack> list = new IndexedStackList<>(FrozenKey.Kind.ITEM);
        ItemStack first = UnifiedDiskCoreTest.variant(1).itemStack(1), second = UnifiedDiskCoreTest.variant(2).itemStack(1);
        var entry = list.add(first, 7); list.add(second, 8);
        assertEquals(7, list.getCount(first)); assertEquals(8, list.getCount(second));
        assertEquals(7, list.get(first, 0).getCount());
        assertEquals(entry.getId(), list.getEntry(first, IComparer.COMPARE_NBT).getId());
        var copy = list.copy(); assertEquals(7, copy.get(entry.getId()).getCount());
        var deletion = list.remove(first, 7);
        assertEquals(1, deletion.getStack().getTag().getInt("variant"));
        assertFalse(deletion.getStack().isEmpty());
        assertNull(list.get(entry.getId())); assertEquals(7, copy.get(entry.getId()).getCount());
        second.setCount(8); assertNotNull(list.get(second, IComparer.COMPARE_NBT | IComparer.COMPARE_QUANTITY));
        second.setCount(9); assertNull(list.get(second, IComparer.COMPARE_NBT | IComparer.COMPARE_QUANTITY));
        assertNotEquals(entry.getId(), list.add(first, 1).getId());
    }

    @Test void externalUnknownCallbacksReconcileOnlyRequestedKey() {
        var first = source();
        ItemStack stone = new ItemStack(Items.STONE);
        when(first.extract(any(), eq(Integer.MAX_VALUE), eq(IComparer.COMPARE_NBT), any())).thenReturn(stone.copyWithCount(19));
        IndexedStackList<ItemStack> list = new IndexedStackList<>(FrozenKey.Kind.ITEM, () -> List.of(first));
        list.add(stone, 19); assertEquals(19, list.getCount(stone));
        when(first.extract(any(), eq(Integer.MAX_VALUE), eq(IComparer.COMPARE_NBT), any())).thenReturn(stone.copyWithCount(12));
        list.remove(stone, 7); assertEquals(12, list.getCount(stone));
    }

    @Test void mutableQueriesCannotChangeCachedIdentityOrIds() {
        IndexedStackList<ItemStack> list = new IndexedStackList<>(FrozenKey.Kind.ITEM);
        ItemStack source = UnifiedDiskCoreTest.variant(1).itemStack(1);
        var first = list.add(source, 7);
        assertEquals(first.getId(), list.add(source, 2).getId());
        source.getTag().putInt("variant", 2);
        assertNull(list.getEntry(source, IComparer.COMPARE_NBT));
        var second = list.add(source, 3);
        assertNotEquals(first.getId(), second.getId());
        assertEquals(9, list.get(first.getId()).getCount());
        assertEquals(1, list.get(first.getId()).getTag().getInt("variant"));
        assertEquals(2, list.size());
        list.clear(); assertTrue(list.isEmpty()); assertNull(list.get(first.getId()));
    }

    @Test void unsupportedNaNStillUsesOriginalExceptionalList() {
        IndexedStackList<ItemStack> list = new IndexedStackList<>(FrozenKey.Kind.ITEM);
        ItemStack source = new ItemStack(Items.STONE); source.getOrCreateTag().putDouble("nan", Double.NaN);
        var added = list.add(source, 7);
        assertEquals(7, list.get(added.getId()).getCount()); assertEquals(1, list.size());
        list.add(new ItemStack(Items.DIAMOND), 3);
        var copy = list.copy(); assertEquals(2, copy.size());
        assertEquals(7, copy.get(added.getId()).getCount());
        list.clear(); assertTrue(list.isEmpty()); assertEquals(2, copy.size());
    }

    @Test void fluidQueriesKeepNullEmptyAndMutableTagsIndependent() {
        IndexedStackList<FluidStack> list = new IndexedStackList<>(FrozenKey.Kind.FLUID);
        FluidStack plain = new FluidStack(Fluids.WATER, 1000), empty = plain.copy(); empty.setTag(new CompoundTag());
        FluidStack variant = plain.copy(); variant.getOrCreateTag().putInt("value", 1);
        list.add(plain, 7); list.add(empty, 8); var first = list.add(variant, 9);
        variant.getTag().putInt("value", 2); assertEquals(0, list.getCount(variant));
        list.add(variant, 10);
        assertEquals(4, list.size()); assertEquals(7, list.getCount(plain)); assertEquals(8, list.getCount(empty));
        assertEquals(1, list.get(first.getId()).getTag().getInt("value"));
        assertEquals(9, list.get(first.getId()).getAmount());
    }
}
