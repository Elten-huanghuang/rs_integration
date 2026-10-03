package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import com.refinedmods.refinedstorage.apiimpl.util.ItemStackList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.RegistryObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeRefinedStorageFluidTest extends BootstrapTest {
    @Test
    void nativeWaterAppearsInFullAndNarrowSnapshotsAndExtractsAsFluid() throws Exception {
        Item tokenItem = InkFluidTestFixtures.tokenItem();
        Field value = RegistryObject.class.getDeclaredField("value");
        value.setAccessible(true);
        Object original = value.get(ModItems.ALCHEMIST_INK_FLUID);
        value.set(ModItems.ALCHEMIST_INK_FLUID, tokenItem);
        try {
            INetwork network = network();
            FluidStack water = new FluidStack(Fluids.WATER, 2000);
            FluidStack lava = new FluidStack(Fluids.LAVA, 500);
            when(network.getFluidStorageCache().getList().getStacks()).thenReturn(List.of(
                    new StackListEntry<>(water), new StackListEntry<>(lava)));
            var driver = new NativeRefinedStorageDriver(network);
            ItemStack requested = InkFluidSupport.token(new FluidStack(Fluids.WATER, 1));
            var ingredient = StrictNBTIngredient.of(requested);
            for (Set<Item> types : List.of(Set.<Item>of(), Set.of(tokenItem))) {
                var read = driver.snapshotItems(types.isEmpty() ? null : types);
                var snapshot = RefinedStorageSnapshotMapper.map(read).snapshot().orElseThrow();
                var match = snapshot.match(ingredient);
                assertTrue(match.successful());
                assertEquals(1, match.items().size());
                assertEquals(2000, match.items().get(0).amount());
            }
            assertTrue(driver.snapshotItems(Set.of(Items.WATER_BUCKET)).items().isEmpty());
            when(network.extractFluid(any(FluidStack.class), eq(750), eq(Action.PERFORM)))
                    .thenReturn(new FluidStack(Fluids.WATER, 750));
            assertEquals(750, driver.extract(requested, 750, false).getCount());
            verify(network, never()).extractItem(any(), anyInt(), any());
        } finally {
            value.set(ModItems.ALCHEMIST_INK_FLUID, original);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fluidChangesInvalidateInventoryAndSubscriptionClosesBothListeners() {
        INetwork network = network();
        StorageItemChangeListener listener = mock(StorageItemChangeListener.class);
        var subscription = new NativeRefinedStorageDriver(network).subscribeItemChanges(listener).orElseThrow();
        ArgumentCaptor<IStorageCacheListener<FluidStack>> fluidListener = ArgumentCaptor.forClass(IStorageCacheListener.class);
        verify(network.getFluidStorageCache()).addListener(fluidListener.capture());
        fluidListener.getValue().onChanged(mock(StackListResult.class));
        verify(listener).onInvalidated();
        assertTrue(subscription.isValid());
        subscription.close();
        subscription.close();
        assertFalse(subscription.isValid());
        verify(network.getFluidStorageCache()).removeListener(fluidListener.getValue());
        verify(network.getItemStorageCache()).removeListener(any());
    }

    private static INetwork network() {
        INetwork network = mock(INetwork.class, RETURNS_DEEP_STUBS);
        when(network.canRun()).thenReturn(true);
        when(network.getItemStorageCache().getList()).thenReturn(new ItemStackList());
        return network;
    }
}
