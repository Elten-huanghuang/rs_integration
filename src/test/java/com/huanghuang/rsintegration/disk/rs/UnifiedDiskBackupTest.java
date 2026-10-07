package com.huanghuang.rsintegration.disk.rs;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedDiskBackupTest extends BootstrapTest {
    @TempDir Path world;
    private MinecraftServer server;
    private ServerLevel level;
    private UnifiedDiskManager manager;
    private DiskFileStore files;

    @BeforeEach void setup() {
        server = mock(MinecraftServer.class); when(server.isSameThread()).thenReturn(true);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        level = mock(ServerLevel.class); when(level.getServer()).thenReturn(server);
        when(server.overworld()).thenReturn(level);
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; })
                .when(server).executeBlocking(any(Runnable.class));
        files = new DiskFileStore(world.resolve("data/rs_integration/unified_disks"));
        manager = UnifiedDiskManager.get(level);
    }

    @AfterEach void stop() { UnifiedDiskManager.stop(server); RSStorageConfig.SPEC.setConfig(null); }

    @Test void backupFreezesDiskFilesWhileInventoryKeepsChangingAndSavesAfterRelease() throws Exception {
        UUID id = manager.create(null).id();
        UnifiedDiskCore core = manager.entry(id).core;
        core.insertItem(new ItemStack(Items.DIAMOND), 7, true);
        core.insertFluid(new FluidStack(Fluids.WATER, 1), 34000, true);
        Path restored = world.resolve("restored");
        UnifiedDiskBackup.run(server, () -> {
            core.insertItem(new ItemStack(Items.DIAMOND), 11, true);
            core.insertFluid(new FluidStack(Fluids.WATER, 1), 1000, true);
            when(server.getTickCount()).thenReturn(100); manager.tick(); manager.flush();
            assertTrue(core.dirty());
            assertThrows(IOException.class, () -> manager.create(null));
            Path sourceRoot = world.resolve("data/rs_integration/unified_disks");
            List<Path> sources;
            try (var paths = Files.walk(sourceRoot)) { sources = paths.filter(Files::isRegularFile).toList(); }
            for (Path source : sources) {
                Path destination = restored.resolve(sourceRoot.relativize(source));
                Files.createDirectories(destination.getParent()); Files.copy(source, destination);
            }
            return null;
        });
        UnifiedDiskCore backup = new DiskFileStore(restored).load(manager.worldId(), id);
        assertEquals(7, backup.items.total()); assertEquals(34000, backup.fluids.total());
        manager.flush();
        assertFalse(core.dirty());
        assertEquals(18, files.load(manager.worldId(), id).items.total());
        assertEquals(35000, files.load(manager.worldId(), id).fluids.total());
    }

    @Test void failedBackupReleasesPauseAndKeepsDirtyInventoryForNextSave() throws Exception {
        UUID id = manager.create(null).id();
        UnifiedDiskCore core = manager.entry(id).core;
        assertThrows(IOException.class, () -> UnifiedDiskBackup.run(server, () -> {
            core.insertItem(new ItemStack(Items.DIAMOND), 19, true);
            throw new IOException("备份读取失败");
        }));
        manager.flush();
        assertEquals(19, files.load(manager.worldId(), id).items.total());
    }

    @Test void missingPageFailsBackupBeforeCopyAndReleasesPause() throws Exception {
        UUID id = manager.create(null).id();
        manager.entry(id).core.insertItem(new ItemStack(Items.DIAMOND), 19, true); manager.flush();
        Path page = world.resolve("data/rs_integration/unified_disks").resolve(id.toString())
                .resolve(files.manifest(id).items().get(0).keys().get(0));
        Files.delete(page);
        assertThrows(IOException.class, () -> UnifiedDiskBackup.run(server, () -> {
            fail("不能继续生成缺页备份"); return null;
        }));
        assertNotNull(manager.create(null));
    }

    @Test void nestedBackupsOnlyResumeAfterLastLeaseAndCloseIsIdempotent() throws Exception {
        UUID id = manager.create(null).id();
        UnifiedDiskManager.BackupLease first = manager.beginBackup();
        try (UnifiedDiskManager.BackupLease second = manager.beginBackup()) {
            manager.entry(id).core.insertItem(new ItemStack(Items.DIAMOND), 11, true);
            first.close(); first.close(); manager.flush();
            assertEquals(0, files.load(manager.worldId(), id).items.total());
        }
        manager.flush();
        assertEquals(11, files.load(manager.worldId(), id).items.total());
    }

    @Test void capacityExpansionIsDeferredDuringBackupAndPersistsAfterwards() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory(); RSStorageConfig.SPEC.correct(config);
        config.set("unifiedDisk.maxItemEntries", 512); config.set("unifiedDisk.maxFluidEntries", 512);
        RSStorageConfig.SPEC.setConfig(config);
        UUID id = manager.create(null).id();
        manager.entry(id).core.insertItem(new ItemStack(Items.DIAMOND), 11, true);
        UnifiedDiskManager.stop(server);
        config.set("unifiedDisk.maxItemEntries", 262144); config.set("unifiedDisk.maxFluidEntries", 262144);
        RSStorageConfig.SPEC.setConfig(config); manager = UnifiedDiskManager.get(level);
        try (UnifiedDiskManager.BackupLease lease = manager.beginBackup()) {
            assertEquals(262144, manager.entry(id).core.limits.items());
            manager.flush();
            assertEquals(512, files.manifest(id).limits().items());
        }
        manager.flush();
        assertEquals(262144, files.manifest(id).limits().items());
        assertEquals(11, files.load(manager.worldId(), id).items.total());
    }

    @Test void shutdownSavesChangesAfterBackupThreadReleasesWithoutServerQueue() throws Exception {
        UUID id = manager.create(null).id(), identity = manager.worldId();
        UnifiedDiskManager.BackupLease lease = manager.beginBackup();
        manager.entry(id).core.insertItem(new ItemStack(Items.DIAMOND), 19, true);
        CompletableFuture<Void> release = CompletableFuture.runAsync(lease::close);
        UnifiedDiskManager.stop(server); release.join();
        assertEquals(19, files.load(identity, id).items.total());
    }
}
