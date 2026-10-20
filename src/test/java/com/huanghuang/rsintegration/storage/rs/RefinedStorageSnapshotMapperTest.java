package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageSnapshotStatus;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinedStorageSnapshotMapperTest extends BootstrapTest {
    @Test
    void nullAndUnavailableResponsesRemainDistinct() {
        var invalid = RefinedStorageSnapshotMapper.map(null);
        assertEquals(StorageSnapshotStatus.INVALID_RESPONSE, invalid.status());
        assertEquals(StorageDiagnosticCode.INVALID_NATIVE_RESPONSE, invalid.diagnosticCode());
        assertEquals(StorageSnapshotStatus.UNAVAILABLE,
                RefinedStorageSnapshotMapper.map(RefinedStorageSnapshotRead.unavailable()).status());
    }

    @Test
    void nanFoodIdentityDoesNotHideOtherMaterials() {
        ItemStack malformed = new ItemStack(Items.BREAD);
        CompoundTag dish = new CompoundTag();
        dish.putFloat("nutrition", Float.NaN);
        malformed.getOrCreateTag().put("dish_attribute", dish);
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 4);
        ItemStack gold = new ItemStack(Items.GOLD_INGOT, 2);
        List<ItemStack> skipped = new ArrayList<>();

        var result = RefinedStorageSnapshotMapper.map(
                RefinedStorageSnapshotRead.available(List.of(iron, malformed, gold)),
                (stack, failure) -> {
                    assertTrue(failure.getMessage().contains("NaN"));
                    skipped.add(stack);
                });

        assertEquals(StorageSnapshotStatus.SUCCESS, result.status());
        assertEquals(List.of(Items.IRON_INGOT, Items.GOLD_INGOT), result.snapshot().orElseThrow()
                .items().stream().map(item -> item.stack().getItem()).toList());
        assertEquals(List.of(4L, 2L), result.snapshot().orElseThrow()
                .items().stream().map(item -> item.amount()).toList());
        assertEquals(1, skipped.size());
        assertTrue(Float.isNaN(malformed.getTag().getCompound("dish_attribute").getFloat("nutrition")));
    }

    @Test
    void validResponsesProduceAnImmutableSnapshot() {
        ItemStack source = new ItemStack(Items.DIAMOND, 3);
        RefinedStorageSnapshotRead read = RefinedStorageSnapshotRead.available(List.of(source));
        source.setCount(1);
        read.items().get(0).setCount(2);
        var result = RefinedStorageSnapshotMapper.map(read);

        assertEquals(StorageSnapshotStatus.SUCCESS, result.status());
        assertEquals(3, result.snapshot().orElseThrow().items().get(0).amount());
    }

    @Test
    void wideBackendAmountReplacesTheNativeIntCacheValue() {
        ItemStack source = new ItemStack(Items.DIAMOND, Integer.MAX_VALUE);
        long exact = Integer.MAX_VALUE + 5_000_000_000L;

        var result = RefinedStorageSnapshotMapper.map(
                RefinedStorageSnapshotRead.available(List.of(source)),
                stack -> exact,
                (stack, failure) -> { });

        assertEquals(exact, result.snapshot().orElseThrow().items().get(0).amount());
    }
}
