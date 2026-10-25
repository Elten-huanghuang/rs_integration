package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageFluidContractTest extends BootstrapTest {
    private static final StorageBackendId BACKEND =
            new StorageBackendId("test_fluid");

    @Test
    void fluidKeyDefensivelyCopiesTagAndIgnoresCompoundKeyOrder() {
        CompoundTag first = new CompoundTag();
        first.putString("name", "water");
        first.putInt("temperature", 20);
        CompoundTag second = new CompoundTag();
        second.putInt("temperature", 20);
        second.putString("name", "water");
        FluidStack stack = new FluidStack(Fluids.WATER, 1000);
        stack.setTag(first);

        StorageFluidKey key = StorageFluidKey.fromStack(BACKEND, stack);
        StorageFluidKey equal = new StorageFluidKey(
                BACKEND,
                ForgeRegistries.FLUIDS.getKey(stack.getFluid()),
                second
        );
        first.putString("mutated", "true");

        assertEquals(key, equal);
        assertTrue(!key.tag().contains("mutated"));
        assertNotEquals(key, new StorageFluidKey(
                BACKEND, key.fluidId(), new CompoundTag()));
    }

    @Test
    void snapshotAggregatesLongAmountsAndSupportsExactOrBroadTagMatching() {
        FluidStack tagged = new FluidStack(Fluids.WATER, 1);
        tagged.getOrCreateTag().putString("kind", "clean");
        StorageFluidKey taggedKey = StorageFluidKey.fromStack(
                BACKEND, tagged);
        StorageFluidKey plainKey = StorageFluidKey.fromStack(
                BACKEND, new FluidStack(Fluids.WATER, 1));
        StorageFluidSnapshot snapshot = new StorageFluidSnapshot(
                BACKEND,
                List.of(
                        new StoredFluid(taggedKey, 5L),
                        new StoredFluid(taggedKey, 7L),
                        new StoredFluid(plainKey, 3L)
                )
        );

        assertEquals(12L, snapshot.countExact(taggedKey));
        assertEquals(12L, snapshot.countMatching(taggedKey, true));
        assertEquals(15L, snapshot.countMatching(taggedKey, false));
    }

    @Test
    void fluidOperationResultTracksPartialLongTransfers() {
        FluidStack fluid = new FluidStack(Fluids.WATER, 1200);
        StorageFluidOperationResult partial =
                StorageFluidOperationResult.extracted(
                        StorageOperationMode.PERFORM,
                        2000L,
                        List.of(fluid)
                );

        assertEquals(StorageOperationStatus.PARTIAL, partial.status());
        assertEquals(1200L,
                partial.transferredAmountExact().orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new StoredFluid(
                        StorageFluidKey.fromStack(BACKEND, fluid), 0L));
    }
}
