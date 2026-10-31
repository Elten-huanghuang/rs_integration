package com.huanghuang.rsintegration.feature.experience;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** 杖内仅保管已扣款的余液；客户端设置包不能写入这些字段。 */
final class ExperienceWandData {
    private static final String KEY = "ExperienceRuntime";
    static final String PENDING_TARGET = "PendingTarget";

    private ExperienceWandData() {}

    static CompoundTag tag(ItemStack stack) {
        CompoundTag root = stack.getOrCreateTag();
        if (!root.contains(KEY)) root.put(KEY, new CompoundTag());
        return root.getCompound(KEY);
    }

    static UUID id(ItemStack stack) {
        CompoundTag tag = tag(stack);
        if (!tag.hasUUID("Id")) tag.putUUID("Id", UUID.randomUUID());
        return tag.getUUID("Id");
    }

    static boolean matches(ItemStack stack, UUID id) {
        if (!(stack.getItem() instanceof ResonantExperienceWandItem)) return false;
        CompoundTag root = stack.getTag();
        if (root == null) return false;
        CompoundTag tag = root.getCompound(KEY);
        return tag.hasUUID("Id") && id.equals(tag.getUUID("Id"));
    }

    static long buffer(ItemStack stack) {
        return Math.max(0L, tag(stack).getLong("BufferedMb"));
    }

    static void buffer(ItemStack stack, long amount) {
        tag(stack).putLong("BufferedMb", Math.max(0L, amount));
    }

    static long uncertain(ItemStack stack) {
        return Math.max(0L, tag(stack).getLong("UncertainMb"));
    }

    static void retainUncertain(ItemStack stack, int amount) {
        tag(stack).putLong("UncertainMb", Math.addExact(uncertain(stack), amount));
    }

    static boolean active(ItemStack stack) {
        if (!(stack.getItem() instanceof ResonantExperienceWandItem)) return false;
        ExperienceWandSettings settings = ExperienceWandSettings.fromStack(stack);
        CompoundTag root = stack.getTag();
        return settings.mode() != ExperienceWandSettings.Mode.OFF || settings.mending()
                || root != null && root.getCompound(KEY).contains(PENDING_TARGET);
    }
}
