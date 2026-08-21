package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageSnapshotStatus;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

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
    void malformedNanIdentityFailsClosed() {
        ItemStack malformed = new ItemStack(Items.DIAMOND);
        malformed.getOrCreateTag().putDouble("value", Double.NaN);

        var result = RefinedStorageSnapshotMapper.map(
                RefinedStorageSnapshotRead.available(List.of(malformed)));

        assertEquals(StorageSnapshotStatus.INVALID_RESPONSE, result.status());
        assertTrue(result.snapshot().isEmpty());
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
}
