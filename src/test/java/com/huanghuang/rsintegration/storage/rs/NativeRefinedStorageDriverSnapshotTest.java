package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.disk.core.FrozenKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeRefinedStorageDriverSnapshotTest extends BootstrapTest {

    @Test
    void restoresDiskOnlyItemsAfterNativeCacheTemporarilyDropsThem() {
        FrozenKey key = FrozenKey.item(new ItemStack(Items.TUFF));
        List<ItemStack> items = new ArrayList<>();

        NativeRefinedStorageDriver.appendMissingDiskItems(
                items, Map.of(key, 99_845_381_177_416L), Set.of(Items.TUFF));

        assertEquals(1, items.size());
        assertTrue(items.get(0).is(Items.TUFF));
        assertEquals(1, items.get(0).getCount());
        var snapshot = RefinedStorageSnapshotMapper.map(
                RefinedStorageSnapshotRead.available(items),
                ignored -> 99_845_381_177_416L,
                (stack, failure) -> { }).snapshot().orElseThrow();
        assertEquals(99_845_381_177_416L, snapshot.items().get(0).amount());
    }

    @Test
    void doesNotDuplicateItemsAlreadyReturnedByTheNativeCache() {
        ItemStack nativeTuff = new ItemStack(Items.TUFF, Integer.MAX_VALUE);
        List<ItemStack> items = new ArrayList<>(List.of(nativeTuff));
        Map<FrozenKey, Long> diskAmounts = Map.of(
                FrozenKey.item(new ItemStack(Items.TUFF)), 5_000_000_000L,
                FrozenKey.item(new ItemStack(Items.DEEPSLATE)), 7_000_000_000L);

        NativeRefinedStorageDriver.appendMissingDiskItems(
                items, diskAmounts, Set.of(Items.TUFF));

        assertEquals(List.of(Items.TUFF), items.stream()
                .map(ItemStack::getItem).toList());
    }

    @Test
    void restoresDiskOnlyFluidsWithoutNarrowingTheirLongAmounts() {
        FrozenKey key = FrozenKey.fluid(new FluidStack(Fluids.WATER, 1));
        List<FluidStack> fluids = new ArrayList<>();

        NativeRefinedStorageDriver.appendMissingDiskFluids(fluids, Map.of(key, Long.MAX_VALUE));

        assertEquals(1, fluids.size());
        assertEquals(key, FrozenKey.queryFluid(fluids.get(0)));
        assertEquals(1, fluids.get(0).getAmount());
    }

    @Test
    void fluidSnapshotDeduplicatesExactIdentitiesAndKeepsDistinctNbt() {
        FluidStack nativeWater = new FluidStack(Fluids.WATER, Integer.MAX_VALUE);
        FluidStack taggedWater = new FluidStack(Fluids.WATER, 1);
        taggedWater.getOrCreateTag().putString("variant", "separate");
        List<FluidStack> fluids = new ArrayList<>(List.of(nativeWater));

        NativeRefinedStorageDriver.appendMissingDiskFluids(fluids, Map.of(
                FrozenKey.fluid(nativeWater), 5_000_000_000L,
                FrozenKey.fluid(taggedWater), 7_000_000_000L));

        assertEquals(2, fluids.size());
        assertEquals(Integer.MAX_VALUE, fluids.get(0).getAmount());
        assertEquals(FrozenKey.fluid(taggedWater), FrozenKey.queryFluid(fluids.get(1)));
        assertEquals(1, fluids.get(1).getAmount());
    }

    @Test
    void fluidSnapshotDoesNotRestoreEmptyDiskEntriesOrItemKeys() {
        List<FluidStack> fluids = new ArrayList<>();

        NativeRefinedStorageDriver.appendMissingDiskFluids(fluids, Map.of(
                FrozenKey.fluid(new FluidStack(Fluids.WATER, 1)), 0L,
                FrozenKey.fluid(new FluidStack(Fluids.LAVA, 1)), -1L,
                FrozenKey.item(new ItemStack(Items.STICK)), Long.MAX_VALUE));

        assertTrue(fluids.isEmpty());
    }
}
