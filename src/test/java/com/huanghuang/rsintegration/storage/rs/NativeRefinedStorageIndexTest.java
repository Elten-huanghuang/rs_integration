package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.IStackList;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.apiimpl.util.ItemStackList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeRefinedStorageIndexTest extends BootstrapTest {
    @Test
    void singleTypeUsesLiveNativeBucketAndRetainsAllNbtVariantsInOrder() {
        ItemStackList list = spy(new ItemStackList());
        ItemStack first = named("first", 2);
        ItemStack second = named("second", 3);
        list.getStacks(first).add(new StackListEntry<>(first));
        list.getStacks(first).add(new StackListEntry<>(second));
        list.getStacks(new ItemStack(Items.GOLD_INGOT)).add(new StackListEntry<>(new ItemStack(Items.GOLD_INGOT, 50)));
        var driver = driver(list);
        clearInvocations(list);
        var snapshot = driver.snapshotItems(Set.of(Items.IRON_INGOT));
        assertEquals(List.of("first", "second"), snapshot.items().stream()
                .map(stack -> stack.getTag().getString("variant")).toList());
        verify(list, never()).getStacks();
        verify(list).getStacks(any(ItemStack.class));
        first.setCount(1);
        second.getOrCreateTag().putInt("charge", 17);
        var fresh = driver.snapshotItems(Set.of(Items.IRON_INGOT)).items();
        assertEquals(1, fresh.get(0).getCount());
        assertEquals(17, fresh.get(1).getTag().getInt("charge"));
        assertEquals(2, snapshot.items().get(0).getCount());
        list.clear();
        assertTrue(driver.snapshotItems(Set.of(Items.IRON_INGOT)).items().isEmpty());
    }

    @Test
    void multiTypeQueryKeepsNativeGlobalOrder() {
        ItemStackList list = spy(new ItemStackList());
        list.getStacks(new ItemStack(Items.GOLD_INGOT)).add(new StackListEntry<>(new ItemStack(Items.GOLD_INGOT)));
        list.getStacks(named("first", 2)).add(new StackListEntry<>(named("first", 2)));
        list.getStacks(named("second", 3)).add(new StackListEntry<>(named("second", 3)));
        var expected = list.getStacks().stream().map(entry -> entry.getStack().save(new net.minecraft.nbt.CompoundTag())).toList();
        clearInvocations(list);
        var actual = driver(list).snapshotItems(Set.of(Items.GOLD_INGOT, Items.IRON_INGOT));
        assertEquals(expected, actual.items().stream().map(stack -> stack.save(new net.minecraft.nbt.CompoundTag())).toList());
        verify(list).getStacks();
        verify(list, never()).getStacks(any(ItemStack.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void customStackListFallsBackToItsFullEnumeration() {
        IStackList<ItemStack> list = mock(IStackList.class);
        when(list.getStacks()).thenReturn(List.of(new StackListEntry<>(named("first", 2)),
                new StackListEntry<>(new ItemStack(Items.GOLD_INGOT, 10))));
        assertEquals(1, driver(list).snapshotItems(Set.of(Items.IRON_INGOT)).items().size());
        verify(list).getStacks();
        verify(list, never()).getStacks(any(ItemStack.class));
    }

    private static NativeRefinedStorageDriver driver(IStackList<ItemStack> list) {
        INetwork network = mock(INetwork.class, RETURNS_DEEP_STUBS);
        when(network.canRun()).thenReturn(true);
        when(network.getItemStorageCache().getList()).thenReturn(list);
        return new NativeRefinedStorageDriver(network);
    }

    private static ItemStack named(String variant, int count) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count);
        stack.getOrCreateTag().putString("variant", variant);
        return stack;
    }
}
