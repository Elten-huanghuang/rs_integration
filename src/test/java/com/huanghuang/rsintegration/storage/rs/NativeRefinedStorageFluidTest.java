package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.storage.disk.core.FrozenKey;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskAmountResolver;
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
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeRefinedStorageFluidTest extends BootstrapTest {
    @Test
    void fluidSnapshotCombinesNativeIdentitiesWithExactDiskAmounts() {
        INetwork network = network();
        FluidStack water = new FluidStack(Fluids.WATER, Integer.MAX_VALUE);
        FrozenKey waterKey = FrozenKey.fluid(water);
        FrozenKey lavaKey = FrozenKey.fluid(new FluidStack(Fluids.LAVA, 1));
        when(network.getFluidStorageCache().getList().getStacks())
                .thenReturn(List.of(new StackListEntry<>(water)));
        try (MockedStatic<UnifiedDiskAmountResolver> amounts =
                     mockStatic(UnifiedDiskAmountResolver.class, CALLS_REAL_METHODS)) {
            amounts.when(() -> UnifiedDiskAmountResolver.snapshot(network, FrozenKey.Kind.FLUID))
                    .thenReturn(Map.of(waterKey, 99_845_381_177_416L, lavaKey, Long.MAX_VALUE));
            amounts.when(() -> UnifiedDiskAmountResolver.exactAmount(network, waterKey))
                    .thenReturn(99_845_381_177_416L);
            amounts.when(() -> UnifiedDiskAmountResolver.exactAmount(network, lavaKey))
                    .thenReturn(Long.MAX_VALUE);
            NativeRefinedStorageDriver driver = new NativeRefinedStorageDriver(network);

            List<FluidStack> snapshot = driver.snapshotFluids();

            assertEquals(2, snapshot.size());
            assertEquals(waterKey, FrozenKey.queryFluid(snapshot.get(0)));
            assertEquals(99_845_381_177_416L,
                    driver.exactStoredFluidAmount(snapshot.get(0), snapshot.get(0).getAmount()));
            assertEquals(lavaKey, FrozenKey.queryFluid(snapshot.get(1)));
            assertEquals(Long.MAX_VALUE,
                    driver.exactStoredFluidAmount(snapshot.get(1), snapshot.get(1).getAmount()));
            assertEquals(Integer.MAX_VALUE, water.getAmount());
        }
    }

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
    void fluidChangesPublishAbsoluteCountsAndSubscriptionClosesBothListeners() {
        INetwork network = network();
        StorageItemChangeListener listener = mock(StorageItemChangeListener.class);
        var subscription = new NativeRefinedStorageDriver(network).subscribeItemChanges(listener).orElseThrow();
        ArgumentCaptor<IStorageCacheListener<FluidStack>> fluidListener = ArgumentCaptor.forClass(IStorageCacheListener.class);
        verify(network.getFluidStorageCache()).addListener(fluidListener.capture());
        FluidStack water = new FluidStack(Fluids.WATER, 1000);
        when(network.getFluidStorageCache().getList().getEntry(any(), anyInt()))
                .thenReturn(new StackListEntry<>(water));
        StackListResult<FluidStack> changed = mock(StackListResult.class);
        when(changed.getStack()).thenReturn(new FluidStack(Fluids.WATER, 250));
        fluidListener.getValue().onChanged(changed);
        verify(listener).onFluidChanged(argThat(stack -> stack.getFluid() == Fluids.WATER
                && stack.getAmount() == 1), eq(1000L));
        when(network.getFluidStorageCache().getList().getEntry(any(), anyInt())).thenReturn(null);
        fluidListener.getValue().onChanged(changed);
        verify(listener).onFluidChanged(any(), eq(0L));
        verify(listener, never()).onInvalidated();
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
