package com.huanghuang.rsintegration.storage;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/** Deterministic NBT encoding compatible with vanilla equality for supported values. */
public final class StorageIdentityBytes {
    private StorageIdentityBytes() {}

    public static byte[] exact(CompoundTag identity) {
        try {
            LimitedByteArrayOutputStream bytes = new LimitedByteArrayOutputStream(
                    StorageItemKey.MAX_BACKEND_PAYLOAD_BYTES);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeTag(output, identity, 0);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to encode storage identity", e);
        }
    }

    static void validateBounds(CompoundTag identity) {
        exact(identity);
    }

    private static void writeTag(DataOutputStream output, Tag tag, int depth) throws IOException {
        if (depth > StorageItemKey.MAX_NBT_DEPTH) {
            throw new IllegalArgumentException("storage item payload is nested too deeply");
        }
        output.writeByte(tag.getId());
        switch (tag.getId()) {
            case Tag.TAG_END -> { }
            case Tag.TAG_BYTE -> output.writeByte(((ByteTag) tag).getAsByte());
            case Tag.TAG_SHORT -> output.writeShort(((ShortTag) tag).getAsShort());
            case Tag.TAG_INT -> output.writeInt(((IntTag) tag).getAsInt());
            case Tag.TAG_LONG -> output.writeLong(((LongTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> {
                float value = ((FloatTag) tag).getAsFloat();
                if (Float.isNaN(value)) {
                    throw new IllegalArgumentException("NaN cannot form a stable vanilla NBT identity");
                }
                output.writeInt(value == 0.0f ? 0 : Float.floatToRawIntBits(value));
            }
            case Tag.TAG_DOUBLE -> {
                double value = ((DoubleTag) tag).getAsDouble();
                if (Double.isNaN(value)) {
                    throw new IllegalArgumentException("NaN cannot form a stable vanilla NBT identity");
                }
                output.writeLong(value == 0.0d ? 0L : Double.doubleToRawLongBits(value));
            }
            case Tag.TAG_BYTE_ARRAY -> {
                byte[] values = ((ByteArrayTag) tag).getAsByteArray();
                output.writeInt(values.length);
                output.write(values);
            }
            case Tag.TAG_STRING -> writeString(output, ((StringTag) tag).getAsString());
            case Tag.TAG_LIST -> {
                ListTag list = (ListTag) tag;
                output.writeInt(list.size());
                for (Tag element : list) writeTag(output, element, depth + 1);
            }
            case Tag.TAG_COMPOUND -> {
                CompoundTag compound = (CompoundTag) tag;
                ArrayList<String> keys = new ArrayList<>(compound.getAllKeys());
                Collections.sort(keys);
                output.writeInt(keys.size());
                for (String key : keys) {
                    writeString(output, key);
                    writeTag(output, compound.get(key), depth + 1);
                }
            }
            case Tag.TAG_INT_ARRAY -> {
                int[] values = ((IntArrayTag) tag).getAsIntArray();
                output.writeInt(values.length);
                for (int value : values) output.writeInt(value);
            }
            case Tag.TAG_LONG_ARRAY -> {
                long[] values = ((LongArrayTag) tag).getAsLongArray();
                output.writeInt(values.length);
                for (long value : values) output.writeLong(value);
            }
            default -> throw new IllegalArgumentException("unsupported NBT tag type: " + tag.getId());
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static final class LimitedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int limit;

        private LimitedByteArrayOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) {
            requireCapacity(length);
            super.write(values, offset, length);
        }

        private void requireCapacity(int additionalBytes) {
            if (additionalBytes < 0 || count > limit - additionalBytes) {
                throw new IllegalArgumentException("storage item payload is too large");
            }
        }
    }
}
