package com.huanghuang.rsintegration.voidupgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public record VoidUpgradeConfig(boolean matchNbt, boolean matchDamage,
                                boolean matchEnchantments, List<VoidUpgradeRule> rules) {
    public static final String ROOT_KEY = "RSVoidUpgrade";
    public static final int MAX_RULES = 64;
    public static final VoidUpgradeConfig EMPTY = new VoidUpgradeConfig(false, false, false, List.of());

    /** Compatibility constructor for callers and tests using the version 1 all-NBT switch. */
    public VoidUpgradeConfig(boolean matchNbt, List<VoidUpgradeRule> rules) {
        this(matchNbt, matchNbt, matchNbt, rules);
    }

    public VoidUpgradeConfig {
        LinkedHashSet<VoidUpgradeRule> unique = new LinkedHashSet<>();
        if (rules != null) {
            for (VoidUpgradeRule rule : rules) {
                if (rule != null && unique.size() < MAX_RULES) unique.add(rule);
            }
        }
        rules = List.copyOf(unique);
    }

    public boolean matches(ItemStack stack) {
        for (VoidUpgradeRule rule : rules) {
            if (rule.matches(stack, matchNbt, matchDamage, matchEnchantments)) return true;
        }
        return false;
    }

    public CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putBoolean("MatchNbt", matchNbt);
        root.putBoolean("MatchDamage", matchDamage);
        root.putBoolean("MatchEnchantments", matchEnchantments);
        ListTag list = new ListTag();
        for (VoidUpgradeRule rule : rules) list.add(rule.toTag());
        root.put("Rules", list);
        return root;
    }

    public void save(ItemStack stack) {
        stack.getOrCreateTag().put(ROOT_KEY, toTag());
    }

    public static VoidUpgradeConfig fromStack(ItemStack stack) {
        CompoundTag itemTag = stack.getTag();
        if (itemTag == null || !itemTag.contains(ROOT_KEY, CompoundTag.TAG_COMPOUND)) return EMPTY;
        return fromTag(itemTag.getCompound(ROOT_KEY));
    }

    public static VoidUpgradeConfig fromTag(CompoundTag root) {
        if (root == null) return EMPTY;
        List<VoidUpgradeRule> rules = new ArrayList<>();
        ListTag list = root.getList("Rules", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && rules.size() < MAX_RULES; i++) {
            VoidUpgradeRule rule = VoidUpgradeRule.fromTag(list.getCompound(i));
            if (rule != null) rules.add(rule);
        }
        boolean matchNbt = root.getBoolean("MatchNbt");
        if (root.getInt("Version") < 2) {
            return new VoidUpgradeConfig(matchNbt, matchNbt, matchNbt, rules);
        }
        return new VoidUpgradeConfig(matchNbt, root.getBoolean("MatchDamage"),
                root.getBoolean("MatchEnchantments"), rules);
    }
}
