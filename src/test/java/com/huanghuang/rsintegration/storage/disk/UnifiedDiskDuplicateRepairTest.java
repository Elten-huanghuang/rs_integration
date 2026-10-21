package com.huanghuang.rsintegration.storage.disk;

import com.huanghuang.rsintegration.storage.disk.core.FrozenKey;
import com.huanghuang.rsintegration.storage.disk.core.ResourceTable.PageSnapshot;
import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.storage.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.storage.disk.persistence.DiskFileStore.Snapshot;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskManager;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnifiedDiskDuplicateRepairTest extends BootstrapTest {
    @TempDir Path root;

    private record Fixture(DiskFileStore files, UnifiedDiskCore core, DiskFileStore.Saved saved,
                           FrozenKey duplicate) {}

    private Fixture duplicateDisk(long firstAmount, long secondAmount) throws Exception {
        return duplicateDisk(root, firstAmount, secondAmount);
    }

    private Fixture duplicateDisk(Path filesRoot, long firstAmount, long secondAmount) throws Exception {
        DiskFileStore files = new DiskFileStore(filesRoot);
        UUID worldId = files.worldIdentity();
        UnifiedDiskCore core = new UnifiedDiskCore(worldId, UUID.randomUUID(), UUID.randomUUID(),
                new UnifiedDiskCore.Limits(8, 8, 1048576, 268435456));
        FrozenKey duplicate = FrozenKey.item(new ItemStack(Items.STONE));
        ItemStack otherStack = new ItemStack(Items.DIAMOND);
        otherStack.getOrCreateTag().putString("variant", "duplicate-repair");
        FrozenKey other = FrozenKey.item(otherStack);
        core.insert(duplicate, firstAmount, true);
        core.insert(other, secondAmount, true);

        Snapshot snapshot = Snapshot.freeze(core);
        int keptSlot = core.items.exactSlot(duplicate);
        int mergedSlot = core.items.exactSlot(other);
        PageSnapshot page = snapshot.items().get(0);
        byte[][] keys = page.keys().clone();
        keys[mergedSlot] = keys[keptSlot].clone();
        PageSnapshot changed = new PageSnapshot(page.index(), page.keyRevision(), page.amountRevision(), keys,
                page.generations(), page.orders(), page.amounts());
        List<PageSnapshot> pages = new ArrayList<>(snapshot.items());
        pages.set(0, changed);
        DiskFileStore.Saved saved = files.save(new Snapshot(snapshot.world(), snapshot.disk(), snapshot.owner(),
                snapshot.limits(), snapshot.itemPages(), snapshot.fluidPages(), pages, snapshot.fluids(),
                snapshot.coreMagic()), null);
        return new Fixture(files, core, saved, duplicate);
    }

    @Test
    void duplicateIdentityMergesAmountsAndRestoresCheckpoint() throws Exception {
        Fixture fixture = duplicateDisk(7, 11);
        UnifiedDiskCore loaded = fixture.files.load(fixture.core.worldId, fixture.core.diskId);

        assertEquals(18, loaded.items.amount(fixture.duplicate));
        assertEquals(1, loaded.items.size());
        assertEquals(1, loaded.items.duplicateRepairs().size());
        assertTrue(loaded.dirty());

        Snapshot repaired = Snapshot.freeze(loaded);
        fixture.files.save(repaired, fixture.saved.manifest());
        loaded.items.acknowledge(repaired.items());
        loaded.fluids.acknowledge(repaired.fluids());
        loaded.acknowledgeMagic(repaired.coreMagic());
        assertFalse(loaded.dirty());

        UnifiedDiskCore reloaded = fixture.files.load(fixture.core.worldId, fixture.core.diskId);
        assertEquals(18, reloaded.items.amount(fixture.duplicate));
        assertTrue(reloaded.items.duplicateRepairs().isEmpty());
        assertFalse(reloaded.dirty());
    }

    @Test
    void unsafeDuplicateOverflowStillFailsWithoutChangingCheckpoint() throws Exception {
        Fixture fixture = duplicateDisk(Long.MAX_VALUE, 1);
        Path manifest = root.resolve(fixture.core.diskId.toString()).resolve("manifest.bin");
        byte[] original = Files.readAllBytes(manifest);

        IOException error = assertThrows(IOException.class,
                () -> fixture.files.load(fixture.core.worldId, fixture.core.diskId));
        assertTrue(error.getMessage().contains("重复资源数量汇总超过单条上限"));
        assertArrayEquals(original, Files.readAllBytes(manifest));
    }

    @Test
    void managerPersistsRepairThroughNormalDirtyCheckpointFlow() throws Exception {
        Fixture fixture = duplicateDisk(root.resolve("data/rs_integration/unified_disks"), 7, 11);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(root);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        UnifiedDiskManager manager = UnifiedDiskManager.get(level);
        try {
            UnifiedDiskManager.Entry entry = manager.entry(fixture.core.diskId);
            assertEquals(18, entry.core.items.amount(fixture.duplicate));
            assertEquals(1, entry.core.items.duplicateRepairs().size());
            assertTrue(entry.core.dirty());

            manager.flush();

            assertFalse(entry.core.dirty());
            UnifiedDiskCore reloaded = fixture.files.load(fixture.core.worldId, fixture.core.diskId);
            assertEquals(18, reloaded.items.amount(fixture.duplicate));
            assertTrue(reloaded.items.duplicateRepairs().isEmpty());
            assertFalse(reloaded.dirty());
        } finally {
            UnifiedDiskManager.stop(server);
        }
    }
}
