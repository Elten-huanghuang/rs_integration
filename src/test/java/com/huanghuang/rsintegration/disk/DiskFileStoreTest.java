package com.huanghuang.rsintegration.disk;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.ResourceTable;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore.Snapshot;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiskFileStoreTest extends BootstrapTest {
    @TempDir Path root;
    @Test void recoveryListsCheckpointIdsWithoutLoadingPagesAndIgnoresTemporaryDirectories() throws Exception {
        DiskFileStore files = new DiskFileStore(root);
        files.worldIdentity();
        UnifiedDiskCore core = UnifiedDiskCoreTest.core(8);
        files.save(Snapshot.freeze(core), null);
        Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        Files.createDirectories(root.resolve("unknown"));
        Files.write(root.resolve("unknown/manifest.bin"), new byte[] {1});
        assertEquals(List.of(core.diskId), files.savedDiskIds());
        assertEquals(0, files.load(core.worldId, core.diskId).items.size());
    }
    private void acknowledge(UnifiedDiskCore core, Snapshot snapshot) {
        core.items.acknowledge(snapshot.items()); core.fluids.acknowledge(snapshot.fluids());
    }

    @Test void expandedEntriesReuseSavedPagesPreserveInventoryAndAcceptNewKeys() throws Exception {
        DiskFileStore files = new DiskFileStore(root);
        UnifiedDiskCore core = UnifiedDiskCoreTest.core(2);
        FrozenKey first = UnifiedDiskCoreTest.variant(1), second = UnifiedDiskCoreTest.variant(2);
        FrozenKey water = FrozenKey.fluid(new FluidStack(Fluids.WATER, 1));
        core.insert(first, Integer.MAX_VALUE, true); core.insert(second, 17, true);
        core.insert(water, 5790, true);
        var old = files.save(Snapshot.freeze(core), null);
        UnifiedDiskCore expanded = files.load(core.worldId, core.diskId, 262144, 262144);
        assertFalse(expanded.dirty());
        assertEquals(262144, expanded.items.capacity()); assertEquals(262144, expanded.fluids.capacity());
        assertEquals(core.diskId, expanded.diskId); assertEquals(core.owner, expanded.owner);
        assertEquals(core.payloadBytes(), expanded.payloadBytes());
        assertEquals(Integer.MAX_VALUE, expanded.items.amount(first)); assertEquals(17, expanded.items.amount(second));
        assertEquals(5790, expanded.fluids.amount(water));
        assertEquals(core.items.exactSlot(first), expanded.items.exactSlot(first));
        var upgraded = files.save(Snapshot.freeze(expanded), old.manifest());
        assertEquals(0, upgraded.keySegmentsWritten()); assertEquals(0, upgraded.amountPagesWritten());
        assertEquals(old.manifest().items(), upgraded.manifest().items());
        assertEquals(old.manifest().fluids(), upgraded.manifest().fluids());
        expanded = files.load(core.worldId, core.diskId, 1, 1);
        assertEquals(262144, expanded.limits.items()); assertEquals(262144, expanded.limits.fluids());
        FrozenKey third = UnifiedDiskCoreTest.variant(3);
        assertEquals(31, expanded.insert(third, 31, true));
        var next = files.save(Snapshot.freeze(expanded), upgraded.manifest());
        UnifiedDiskCore loaded = files.load(core.worldId, core.diskId);
        assertEquals(31, loaded.items.amount(third)); assertEquals(Integer.MAX_VALUE, loaded.items.amount(first));
        assertEquals(5790, loaded.fluids.amount(water));
        assertEquals(262144, next.manifest().limits().items());
    }

    @Test void failedExpansionCommitKeepsOldCapacityAndInventoryIncludingEmptyDisk() throws Exception {
        DiskFileStore files = new DiskFileStore(root);
        for (boolean empty : List.of(false, true)) {
            UnifiedDiskCore core = UnifiedDiskCoreTest.core(65536);
            FrozenKey key = UnifiedDiskCoreTest.variant(1);
            if (!empty) core.insert(key, 447, true);
            var old = files.save(Snapshot.freeze(core), null);
            UnifiedDiskCore expanded = files.load(core.worldId, core.diskId, 262144, 262144);
            DiskFileStore broken = new DiskFileStore(root, () -> { throw new IOException("扩容提交失败"); });
            assertThrows(IOException.class, () -> broken.save(Snapshot.freeze(expanded), old.manifest()));
            UnifiedDiskCore original = files.load(core.worldId, core.diskId);
            assertEquals(65536, original.limits.items()); assertEquals(empty ? 0 : 447, original.items.amount(key));
            files.save(Snapshot.freeze(expanded), old.manifest());
            assertEquals(262144, files.load(core.worldId, core.diskId).limits.items());
        }
    }

    @Test void saveRejectsCapacityReductionAndPayloadLimitChanges() throws Exception {
        DiskFileStore files = new DiskFileStore(root);
        UnifiedDiskCore core = UnifiedDiskCoreTest.core(1024);
        var saved = files.save(Snapshot.freeze(core), null);
        for (var limits : List.of(new UnifiedDiskCore.Limits(512, 1024, 1048576, 268435456),
                new UnifiedDiskCore.Limits(2048, 2048, 1024, 268435456))) {
            UnifiedDiskCore changed = new UnifiedDiskCore(core.worldId, core.diskId, core.owner, limits);
            assertThrows(IOException.class, () -> files.save(Snapshot.freeze(changed), saved.manifest()));
        }
        assertEquals(core.limits, files.manifest(core.diskId).limits());
    }
    @Test void bothTablesRoundTripWithIntMaxAndAmountOnlyWrites() throws Exception {
        DiskFileStore files = new DiskFileStore(root);
        UnifiedDiskCore core = UnifiedDiskCoreTest.core(1024);
        FrozenKey stone = FrozenKey.item(new ItemStack(Items.STONE));
        FrozenKey water = FrozenKey.fluid(new FluidStack(Fluids.WATER, 1));
        core.insert(stone, Integer.MAX_VALUE, true); core.insert(water, Integer.MAX_VALUE, true);
        Snapshot first = Snapshot.freeze(core);
        var saved = files.save(first, null); acknowledge(core, first);
        assertEquals(2, saved.keySegmentsWritten()); assertEquals(2, saved.amountPagesWritten());
        UnifiedDiskCore loaded = files.load(core.worldId, core.diskId);
        assertEquals(Integer.MAX_VALUE, loaded.items.amount(stone)); assertEquals(Integer.MAX_VALUE, loaded.fluids.amount(water));
        assertFalse(loaded.dirty()); assertEquals(core.payloadBytes(), loaded.payloadBytes());
        assertEquals(loaded.items.exactSlot(stone), loaded.items.exactSlot(new ItemStack(Items.STONE, 64)));
        assertEquals(loaded.fluids.exactSlot(water), loaded.fluids.exactSlot(new FluidStack(Fluids.WATER, 1000)));
        core.extract(FrozenKey.Kind.ITEM, core.items.exactSlot(stone), 3, true);
        Snapshot second = Snapshot.freeze(core);
        var changed = files.save(second, saved.manifest()); acknowledge(core, second);
        assertEquals(0, changed.keySegmentsWritten()); assertEquals(1, changed.amountPagesWritten());
        assertEquals(saved.manifest().items().get(0).keys(), changed.manifest().items().get(0).keys());
        assertEquals(Integer.MAX_VALUE - 3, files.load(core.worldId, core.diskId).items.amount(stone));
    }

    @Test void failedManifestCommitKeepsBothOldTables() throws Exception {
        DiskFileStore files = new DiskFileStore(root);
        UnifiedDiskCore core = UnifiedDiskCoreTest.core(1024);
        FrozenKey stone = UnifiedDiskCoreTest.variant(1), water = FrozenKey.fluid(new FluidStack(Fluids.WATER, 1));
        core.insert(stone, 7, true); core.insert(water, 11, true);
        Snapshot first = Snapshot.freeze(core); var saved = files.save(first, null); acknowledge(core, first);
        core.insert(stone, 4, true); core.insert(water, 8, true);
        DiskFileStore broken = new DiskFileStore(root, () -> { throw new IOException("故障注入"); });
        assertThrows(IOException.class, () -> broken.save(Snapshot.freeze(core), saved.manifest()));
        UnifiedDiskCore loaded = files.load(core.worldId, core.diskId);
        assertEquals(7, loaded.items.amount(stone)); assertEquals(11, loaded.fluids.amount(water));
        assertTrue(core.dirty());
    }

    @Test void corruptOrMissingPageAndForeignWorldCannotBecomeEmptyStorage() throws Exception {
        DiskFileStore files = new DiskFileStore(root);
        UnifiedDiskCore core = UnifiedDiskCoreTest.core(512);
        core.insert(UnifiedDiskCoreTest.variant(1), 7, true);
        var saved = files.save(Snapshot.freeze(core), null);
        assertThrows(IOException.class, () -> files.load(UUID.randomUUID(), core.diskId));
        assertThrows(IOException.class, () -> files.load(core.worldId, UUID.randomUUID()));
        Path amountPage = root.resolve(core.diskId.toString()).resolve(saved.manifest().items().get(0).amounts());
        byte[] bytes = Files.readAllBytes(amountPage); bytes[30] ^= 1; Files.write(amountPage, bytes);
        assertThrows(IOException.class, () -> files.load(core.worldId, core.diskId));
    }

    @Test void deletedSlotReusedAfterReloadPreservesTypeBucketOrder() throws Exception {
        DiskFileStore files = new DiskFileStore(root);
        UnifiedDiskCore core = UnifiedDiskCoreTest.core(512);
        FrozenKey one = UnifiedDiskCoreTest.variant(1), two = UnifiedDiskCoreTest.variant(2), three = UnifiedDiskCoreTest.variant(3);
        core.insert(one, 1, true); core.insert(two, 1, true);
        ResourceTable.Handle old = core.items.handle(one);
        core.extract(FrozenKey.Kind.ITEM, old.slot(), 1, true); core.insert(three, 1, true);
        files.save(Snapshot.freeze(core), null);
        UnifiedDiskCore loaded = files.load(core.worldId, core.diskId);
        assertEquals(two, loaded.items.key(loaded.items.first(Items.STONE)));
        assertFalse(loaded.items.valid(old));
        assertEquals(2, loaded.items.size());
    }

    @Test void largeKeyPagesAreSplitIntoBoundedSegments() throws Exception {
        DiskFileStore files = new DiskFileStore(root);
        UnifiedDiskCore core = UnifiedDiskCoreTest.core(512);
        for (int i = 0; i < 10; i++) {
            ItemStack stack = new ItemStack(Items.STONE);
            byte[] bytes = new byte[500000]; Arrays.fill(bytes, (byte) i);
            stack.getOrCreateTag().putByteArray("large", bytes);
            core.insert(FrozenKey.item(stack), 1, true);
        }
        var saved = files.save(Snapshot.freeze(core), null);
        assertTrue(saved.manifest().items().get(0).keys().size() >= 2);
        assertEquals(10, files.load(core.worldId, core.diskId).items.size());
    }

    @Test void worldIdentityIsStableAndCorruptionFails() throws Exception {
        DiskFileStore files = new DiskFileStore(root);
        UUID world = files.worldIdentity(); assertEquals(world, files.worldIdentity());
        Files.write(root.resolve("world.bin"), new byte[5]);
        assertThrows(IOException.class, files::worldIdentity);
    }
}
