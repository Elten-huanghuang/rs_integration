package com.huanghuang.rsintegration.disk.rs;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/** 在备份遍历文件期间固定统一盘检查点；异常退出同样恢复后续保存。 */
public final class UnifiedDiskBackup {
    @FunctionalInterface
    public interface Operation<T> { T run() throws IOException; }

    private UnifiedDiskBackup() {}

    public static <T> T run(MinecraftServer server, Operation<T> operation) throws IOException {
        AtomicReference<UnifiedDiskManager.BackupLease> lease = new AtomicReference<>();
        AtomicReference<IOException> failure = new AtomicReference<>();
        server.executeBlocking(() -> {
            UnifiedDiskManager manager = UnifiedDiskManager.existing(server);
            if (manager == null) manager = UnifiedDiskManager.get(server.overworld());
            try { lease.set(manager.beginBackup()); }
            catch (IOException e) { failure.set(e); }
        });
        if (failure.get() != null) throw failure.get();
        try (UnifiedDiskManager.BackupLease active = lease.get()) {
            if (active != null) active.prepareFiles();
            return operation.run();
        }
    }
}
