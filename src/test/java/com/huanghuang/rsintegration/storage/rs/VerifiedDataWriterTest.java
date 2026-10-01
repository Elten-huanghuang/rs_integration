package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.saveddata.SavedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedDataWriterTest extends BootstrapTest {
    @TempDir Path directory;

    @Test
    void cleanDataSkipsSerializationAndDirtyDataRoundTrips() throws IOException {
        Fixture data = new Fixture();
        Path file = directory.resolve("storage.dat");
        VerifiedDataWriter.save(data, file.toFile());
        assertEquals(0, data.calls);
        assertFalse(Files.exists(file));
        data.setDirty();
        VerifiedDataWriter.save(data, file.toFile());
        assertFalse(data.isDirty());
        assertEquals(Integer.MAX_VALUE, NbtIo.readCompressed(file.toFile()).getCompound("data").getInt("amount"));
        assertFalse(Files.exists(directory.resolve("storage.dat.temp")));
    }

    @Test
    void failedReplacementRetainsOldDataDirtyFlagAndRetryableTemporaryFile() throws IOException {
        Path file = directory.resolve("storage.dat");
        Files.writeString(file, "上一版文件");
        Fixture data = new Fixture();
        data.setDirty();
        VerifiedDataWriter.save(data, file.toFile(), (source, target, atomic) -> {
            throw new IOException("模拟文件被占用");
        });
        assertTrue(data.isDirty());
        assertEquals("上一版文件", Files.readString(file));
        assertEquals(Integer.MAX_VALUE, NbtIo.readCompressed(directory.resolve("storage.dat.temp").toFile())
                .getCompound("data").getInt("amount"));
        VerifiedDataWriter.save(data, file.toFile());
        assertFalse(data.isDirty());
    }

    @Test
    void unsupportedAtomicMoveCreatesBackupBeforeReplacement() throws IOException {
        Path file = directory.resolve("storage.dat");
        Files.writeString(file, "上一版文件");
        Fixture data = new Fixture();
        data.setDirty();
        AtomicInteger attempts = new AtomicInteger();
        VerifiedDataWriter.save(data, file.toFile(), (source, target, atomic) -> {
            attempts.incrementAndGet();
            if (atomic) throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "模拟文件系统");
            assertEquals("上一版文件", Files.readString(directory.resolve("storage.dat.bak")));
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        });
        assertEquals(2, attempts.get());
        assertFalse(data.isDirty());
        assertEquals(Integer.MAX_VALUE, NbtIo.readCompressed(file.toFile()).getCompound("data").getInt("amount"));
    }

    @Test
    void serializationFailureDoesNotReplaceOldFile() throws IOException {
        Path file = directory.resolve("storage.dat");
        Files.writeString(file, "上一版文件");
        SavedData data = new SavedData() {
            @Override public CompoundTag save(CompoundTag tag) {
                throw new IllegalStateException("模拟序列化失败");
            }
        };
        data.setDirty();
        VerifiedDataWriter.save(data, file.toFile());
        assertTrue(data.isDirty());
        assertEquals("上一版文件", Files.readString(file));
    }

    private static final class Fixture extends SavedData {
        private int calls;

        @Override public CompoundTag save(CompoundTag tag) {
            calls++;
            tag.putInt("amount", Integer.MAX_VALUE);
            return tag;
        }
    }
}
