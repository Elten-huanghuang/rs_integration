package com.huanghuang.rsintegration.storage.rs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 服务端同步保存：旧文件不先删除，失败不把内存数据标记成已保存。 */
public final class VerifiedDataWriter {
    private static final Logger LOGGER = LogManager.getLogger(VerifiedDataWriter.class);

    private VerifiedDataWriter() {}

    public static void save(SavedData data, File file) {
        save(data, file, VerifiedDataWriter::move);
    }

    static void save(SavedData data, File file, Replacement replacement) {
        if (!data.isDirty()) return;
        try {
            CompoundTag root = new CompoundTag();
            root.put("data", data.save(new CompoundTag()));
            NbtUtils.addCurrentDataVersion(root);
            Path target = file.toPath();
            Path temporary = target.resolveSibling(target.getFileName() + ".temp");
            NbtIo.writeCompressed(root, temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (!root.equals(NbtIo.readCompressed(temporary.toFile()))) {
                throw new IOException("临时存储文件校验失败：" + temporary);
            }
            try {
                replacement.move(temporary, target, true);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // 保留上一版；非原子文件系统无法保证替换过程的断电原子性。
                if (Files.exists(target)) {
                    Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                replacement.move(temporary, target, false);
            }
            data.setDirty(false);
        } catch (Exception exception) {
            data.setDirty();
            LOGGER.error("[RSI] 存储数据保存失败，保留脏标记等待重试：{}；请保留 .temp/.bak 文件", file, exception);
        }
    }

    private static void move(Path source, Path target, boolean atomic) throws IOException {
        if (atomic) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        else Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @FunctionalInterface
    interface Replacement {
        void move(Path source, Path target, boolean atomic) throws IOException;
    }
}
