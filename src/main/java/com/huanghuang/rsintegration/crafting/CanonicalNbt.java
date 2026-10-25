package com.huanghuang.rsintegration.crafting;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 为内部身份提供稳定的 CompoundTag 顺序，不改变 NBT 类型或列表顺序。 */
public final class CanonicalNbt {
    private CanonicalNbt() {}

    @Nullable
    public static String exactSnbt(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return null;
        return canonicalCopy(tag).toString();
    }

    @Nullable
    public static String normalizeSnbt(@Nullable String snbt) {
        if (snbt == null || snbt.isBlank()) return null;
        try {
            return exactSnbt(TagParser.parseTag(snbt));
        } catch (Exception ignored) {
            // 保留无法解析的历史字符串，由现有运行时校验决定是否拒绝。
            return snbt;
        }
    }

    public static CompoundTag canonicalCopy(CompoundTag tag) {
        CompoundTag result = new CompoundTag();
        List<String> keys = new ArrayList<>(tag.getAllKeys());
        keys.sort(String::compareTo);
        for (String key : keys) {
            result.put(key, canonicalCopy(tag.get(key)));
        }
        return result;
    }

    private static Tag canonicalCopy(Tag tag) {
        if (tag instanceof CompoundTag compound) return canonicalCopy(compound);
        if (tag instanceof ListTag list) {
            ListTag result = new ListTag();
            for (Tag element : list) {
                result.add(canonicalCopy(element));
            }
            return result;
        }
        return tag.copy();
    }
}
