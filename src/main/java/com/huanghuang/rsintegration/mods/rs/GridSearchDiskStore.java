package com.huanghuang.rsintegration.mods.rs;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Versioned, bounded storage for stable RS tooltip search text. */
final class GridSearchDiskStore {
    static final int SCHEMA = 2;
    private static final int MAGIC = 0x52534953; // RSIS
    private static final int MAX_STRING_BYTES = 1_048_576;

    record LoadResult(Map<String, String> entries, boolean accepted, boolean corrupt) {}

    private record EncodedEntry(byte[] key, byte[] text) {}

    private GridSearchDiskStore() {}

    static LoadResult load(Path path, String expectedContext, int maxEntries, long maxBytes) {
        if (!Files.isRegularFile(path)) return new LoadResult(Map.of(), true, false);
        try {
            if (Files.size(path) > maxBytes) return new LoadResult(Map.of(), false, false);
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(Files.newInputStream(path))))) {
                if (input.readInt() != MAGIC || input.readInt() != SCHEMA) {
                    return new LoadResult(Map.of(), false, false);
                }
                String context = readString(input, maxBytes);
                if (!expectedContext.equals(context)) {
                    return new LoadResult(Map.of(), false, false);
                }
                int count = input.readInt();
                if (count < 0 || count > maxEntries) throw new IOException("Invalid entry count");
                long decodedBytes = context.getBytes(StandardCharsets.UTF_8).length;
                Map<String, String> entries = new LinkedHashMap<>(Math.max(16, count));
                for (int i = 0; i < count; i++) {
                    String key = readString(input, maxBytes - decodedBytes);
                    decodedBytes += key.getBytes(StandardCharsets.UTF_8).length;
                    String text = readString(input, maxBytes - decodedBytes);
                    decodedBytes += text.getBytes(StandardCharsets.UTF_8).length;
                    if (decodedBytes > maxBytes) throw new IOException("Decoded cache exceeds limit");
                    entries.put(key, text);
                }
                return new LoadResult(Map.copyOf(entries), true, false);
            }
        } catch (IOException | RuntimeException ignored) {
            return new LoadResult(Map.of(), false, true);
        }
    }

    static int save(Path path, String context, Map<String, String> source,
                    int maxEntries, long maxBytes) throws IOException {
        List<Map.Entry<String, String>> sourceEntries = new ArrayList<>(source.entrySet());
        List<EncodedEntry> selected = new ArrayList<>(Math.min(maxEntries, sourceEntries.size()));
        long encodedBytes = utf8Length(context) + Integer.BYTES * 4L;
        for (int index = sourceEntries.size() - 1;
             index >= 0 && selected.size() < maxEntries; index--) {
            Map.Entry<String, String> entry = sourceEntries.get(index);
            byte[] key = encode(entry.getKey());
            byte[] text = encode(entry.getValue());
            long added = Integer.BYTES * 2L + key.length + text.length;
            if (key.length > MAX_STRING_BYTES || text.length > MAX_STRING_BYTES
                    || encodedBytes + added > maxBytes) {
                continue;
            }
            selected.add(new EncodedEntry(key, text));
            encodedBytes += added;
        }

        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(temporary))))) {
                output.writeInt(MAGIC);
                output.writeInt(SCHEMA);
                writeString(output, encode(context));
                output.writeInt(selected.size());
                for (int index = selected.size() - 1; index >= 0; index--) {
                    EncodedEntry entry = selected.get(index);
                    writeString(output, entry.key());
                    writeString(output, entry.text());
                }
            }
            moveAtomically(temporary, path);
            return selected.size();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String readString(DataInputStream input, long remainingBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > remainingBytes) {
            throw new IOException("Invalid string length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated cache string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] encode(@Nullable String value) {
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
