package com.huanghuang.rsintegration.feature.experience;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public record ExperienceWandSettings(Mode mode, int targetLevel, boolean mending, int manualLevels) {
    public enum Mode { OFF, STORE, TAKE, KEEP }

    private static final String KEY = "ExperienceSettings";

    public ExperienceWandSettings {
        if (mode == null) mode = Mode.OFF;
        targetLevel = Math.max(0, Math.min(ExperiencePoints.MAX_LEVEL, targetLevel));
        manualLevels = Math.max(1, Math.min(ExperiencePoints.MAX_LEVEL, manualLevels));
    }

    public static ExperienceWandSettings fromStack(ItemStack stack) {
        CompoundTag root = stack.getTag();
        CompoundTag tag = root == null ? new CompoundTag() : root.getCompound(KEY);
        int ordinal = tag.getInt("Mode");
        Mode mode = ordinal >= 0 && ordinal < Mode.values().length
                ? Mode.values()[ordinal] : Mode.OFF;
        return new ExperienceWandSettings(mode,
                tag.contains("TargetLevel") ? tag.getInt("TargetLevel") : 30,
                tag.getBoolean("Mending"),
                tag.contains("ManualLevels") ? tag.getInt("ManualLevels") : 1);
    }

    public void save(ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Mode", mode.ordinal());
        tag.putInt("TargetLevel", targetLevel);
        tag.putBoolean("Mending", mending);
        tag.putInt("ManualLevels", manualLevels);
        stack.getOrCreateTag().put(KEY, tag);
    }
}
