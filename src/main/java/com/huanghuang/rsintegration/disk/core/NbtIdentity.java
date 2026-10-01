package com.huanghuang.rsintegration.disk.core;

import com.huanghuang.rsintegration.storage.StorageItemKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 与规范编码相同的身份语义；热路径无需排序 Compound 键或生成字节流。 */
final class NbtIdentity {
    private int bytes;
    private boolean malformed;

    record Result(int hash, boolean malformed) {}
    static Result inspect(CompoundTag tag) {
        NbtIdentity visitor = new NbtIdentity();
        int hash = visitor.visit(tag, 0);
        return new Result(hash, visitor.malformed);
    }

    private void add(long count) {
        if (count > FrozenKey.MAX_BYTES - bytes) throw new IllegalArgumentException("统一盘资源身份过大");
        bytes += (int) count;
    }

    private int string(String value) {
        add(4);
        int hash = 0;
        // UTF-8 的非法代理字符与 String.getBytes 一样编码成一个问号。
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            hash = 31 * hash + (Character.isSurrogate(c) && !(Character.isHighSurrogate(c)
                    && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) ? '?' : c);
            if (c < 128) add(1);
            else if (c < 2048) add(2);
            else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) { add(4); hash = 31 * hash + value.charAt(++i); }
            else {
                add(Character.isSurrogate(c) ? 1 : 3);
                malformed |= Character.isSurrogate(c);
            }
        }
        return hash;
    }

    private int visit(Tag tag, int depth) {
        if (depth > StorageItemKey.MAX_NBT_DEPTH) throw new IllegalArgumentException("资源 NBT 嵌套过深");
        add(1);
        int value;
        switch (tag.getId()) {
            case Tag.TAG_END -> value = 0;
            case Tag.TAG_BYTE -> { add(1); value = tag.hashCode(); }
            case Tag.TAG_SHORT -> { add(2); value = tag.hashCode(); }
            case Tag.TAG_INT -> { add(4); value = tag.hashCode(); }
            case Tag.TAG_LONG -> { add(8); value = tag.hashCode(); }
            case Tag.TAG_FLOAT -> {
                add(4); float number = ((FloatTag) tag).getAsFloat();
                if (Float.isNaN(number)) throw new IllegalArgumentException("NaN 不能形成稳定资源身份");
                value = number == 0 ? 0 : Float.floatToRawIntBits(number);
            }
            case Tag.TAG_DOUBLE -> {
                add(8); double number = ((DoubleTag) tag).getAsDouble();
                if (Double.isNaN(number)) throw new IllegalArgumentException("NaN 不能形成稳定资源身份");
                value = Long.hashCode(number == 0 ? 0 : Double.doubleToRawLongBits(number));
            }
            case Tag.TAG_STRING -> value = string(tag.getAsString());
            case Tag.TAG_BYTE_ARRAY -> { add(4L + ((ByteArrayTag) tag).size()); value = tag.hashCode(); }
            case Tag.TAG_INT_ARRAY -> { add(4L + 4L * ((IntArrayTag) tag).size()); value = tag.hashCode(); }
            case Tag.TAG_LONG_ARRAY -> { add(4L + 8L * ((LongArrayTag) tag).size()); value = tag.hashCode(); }
            case Tag.TAG_LIST -> {
                ListTag list = (ListTag) tag; add(4); value = 1;
                for (int i = 0; i < list.size(); i++) value = 31 * value + visit(list.get(i), depth + 1);
            }
            case Tag.TAG_COMPOUND -> {
                CompoundTag compound = (CompoundTag) tag; add(4); value = 0;
                for (String name : compound.getAllKeys()) {
                    int nameHash = string(name);
                    value += nameHash ^ Integer.rotateLeft(visit(compound.get(name), depth + 1), 13);
                }
            }
            default -> throw new IllegalArgumentException("不支持的资源 NBT 类型");
        }
        return 31 * tag.getId() + value;
    }

    static boolean equal(Tag first, Tag second) {
        if (first == second) return true;
        if (first == null || second == null || first.getId() != second.getId()) return false;
        if (first instanceof CompoundTag a) {
            CompoundTag b = (CompoundTag) second;
            if (a.size() != b.size()) return false;
            for (String name : a.getAllKeys()) if (!equal(a.get(name), b.get(name))) return false;
            return true;
        }
        if (first instanceof ListTag a) {
            ListTag b = (ListTag) second;
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equal(a.get(i), b.get(i))) return false;
            return true;
        }
        if (first instanceof FloatTag a) return a.getAsFloat() == ((FloatTag) second).getAsFloat();
        if (first instanceof DoubleTag a) return a.getAsDouble() == ((DoubleTag) second).getAsDouble();
        return first.equals(second);
    }
}
