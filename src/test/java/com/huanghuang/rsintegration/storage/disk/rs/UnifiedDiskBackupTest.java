package com.huanghuang.rsintegration.storage.disk.rs;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.magic.MagicResourceDiskStore;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.storage.disk.persistence.DiskFileStore;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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

    @Test void oldFullBackupOverwritesNewSaveAndSurvivesAnotherSaveAndRestart() throws Exception {
        UUID id = manager.create(null).id(), identity = manager.worldId();
        UnifiedDiskCore core = manager.entry(id).core;
        core.insertItem(new ItemStack(Items.DIAMOND), 7, true);
        core.insertFluid(new FluidStack(Fluids.WATER, 1), 34000, true);
        core.insertMagic(MagicResourceKind.MANA, 23000, true);
        Path sourceRoot = world.resolve("data/rs_integration/unified_disks");
        Path archive = world.resolve("old-backup.zip");
        UnifiedDiskBackup.run(server, () -> {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive));
                 var paths = Files.walk(sourceRoot)) {
                for (Path source : paths.filter(Files::isRegularFile).toList()) {
                    output.putNextEntry(new ZipEntry(world.relativize(source).toString().replace('\\', '/')));
                    Files.copy(source, output);
                    output.closeEntry();
                }
            }
            return null;
        });
        Path directory = sourceRoot.resolve(id.toString());
        Path oldAmount = directory.resolve(files.manifest(id).items().get(0).amounts());
        Files.setLastModifiedTime(oldAmount,
                FileTime.fromMillis(System.currentTimeMillis() - Duration.ofDays(2).toMillis()));
        for (int i = 0; i < 3; i++) {
            core.insertItem(new ItemStack(Items.DIAMOND), 11, true);
            core.insertFluid(new FluidStack(Fluids.WATER, 1), 1000, true);
            core.insertMagic(MagicResourceKind.MANA, 100, true);
            manager.flush();
        }
        assertFalse(Files.exists(oldAmount), "确保旧备份所需的页面已在新存档中回收");
        UUID newerDisk = manager.create(null).id();
        UnifiedDiskManager.stop(server);

        // 模拟退出游戏后直接解压覆盖，保留新存档中多出的目录和页面。
        try (ZipFile backup = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : backup.stream().toList()) {
                Path destination = world.resolve(entry.getName());
                Files.createDirectories(destination.getParent());
                try (var input = backup.getInputStream(entry)) {
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        manager = UnifiedDiskManager.get(level);
        assertEquals(identity, manager.worldId());
        assertTrue(Files.exists(sourceRoot.resolve(newerDisk.toString()).resolve("manifest.bin")));
        core = manager.entry(id).core;
        assertNotNull(core);
        assertEquals(7, core.items.total());
        assertEquals(34000, core.fluids.total());
        assertEquals(23000, core.magicAmount(MagicResourceKind.MANA));
        core.insertItem(new ItemStack(Items.DIAMOND), 2, true);
        UnifiedDiskManager.stop(server);
        manager = UnifiedDiskManager.get(level);
        assertEquals(9, manager.entry(id).core.items.total());
        assertEquals(34000, manager.entry(id).core.fluids.total());
        assertEquals(23000, manager.entry(id).core.magicAmount(MagicResourceKind.MANA));
    }

    @Test void corruptMagicResourceFileFailsBackupBeforeCopyAndReleasesPause() throws Exception {
        UUID id = manager.create(null).id();
        Path magic = world.resolve("data/rs_integration/unified_disks").resolve(id.toString())
                .resolve(MagicResourceDiskStore.NAME);
        Files.write(magic, new byte[] {1});
        assertThrows(IOException.class, () -> UnifiedDiskBackup.run(server, () -> {
            fail("不能继续生成魔法资源文件损坏的备份"); return null;
        }));
        assertNotNull(manager.create(null));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void freezingBeforeFileEnumerationPreventsMissingThirdAmountPage(boolean freeze) throws Exception {
        UUID id = manager.create(null).id();
        UnifiedDiskCore core = manager.entry(id).core;
        for (int index = 0; index < 1025; index++) {
            ItemStack stack = new ItemStack(Items.DIAMOND);
            stack.getOrCreateTag().putInt("variant", index);
            core.insertItem(stack, 1, true);
        }
        manager.flush();
        ItemStack changed = new ItemStack(Items.DIAMOND);
        changed.getOrCreateTag().putInt("variant", 1024);
        Path sourceRoot = world.resolve("data/rs_integration/unified_disks");
        Path restored = world.resolve("restored");
        UnifiedDiskBackup.Operation<Void> copy = () -> {
            List<Path> sources;
            try (var paths = Files.walk(sourceRoot)) { sources = paths.filter(Files::isRegularFile).toList(); }
            // 复现 FTB 遍历期间的服务器保存：新数量页在枚举之后生成，manifest 却已更新。
            core.insertItem(changed, 11, true);
            manager.flush();
            for (Path source : sources) {
                Path destination = restored.resolve(sourceRoot.relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
            return null;
        };
        if (freeze) UnifiedDiskBackup.run(server, copy);
        else copy.run();
        DiskFileStore backup = new DiskFileStore(restored);
        if (freeze) {
            assertEquals(1025, backup.load(manager.worldId(), id).items.total());
        } else {
            NoSuchFileException missing = assertThrows(NoSuchFileException.class, () -> backup.load(manager.worldId(), id));
            assertEquals(files.manifest(id).items().get(2).amounts(), Path.of(missing.getFile()).getFileName().toString());
        }
        manager.flush();
        assertEquals(1036, files.load(manager.worldId(), id).items.total());
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
