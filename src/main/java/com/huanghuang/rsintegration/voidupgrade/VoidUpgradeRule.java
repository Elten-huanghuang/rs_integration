package com.huanghuang.rsintegration.voidupgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.tags.TagKey;

import java.util.Locale;
import java.util.Objects;

public record VoidUpgradeRule(Type type, String value, CompoundTag itemNbt) {
    public enum Type {
        ITEM,
        TAG,
        MOD,
        NAME,
        /** Kept so upgrades configured by older builds can still be loaded. */
        EQUIPMENT
    }

    public VoidUpgradeRule {
        Objects.requireNonNull(type, "type");
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        itemNbt = itemNbt == null ? null : itemNbt.copy();
    }

    public static VoidUpgradeRule item(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new VoidUpgradeRule(Type.ITEM, id == null ? "minecraft:air" : id.toString(),
                stack.hasTag() ? stack.getTag() : null);
    }

    public static VoidUpgradeRule tag(ResourceLocation id) {
        return new VoidUpgradeRule(Type.TAG, id.toString(), null);
    }

    public static VoidUpgradeRule mod(String modId) {
        return new VoidUpgradeRule(Type.MOD, modId, null);
    }

    public static VoidUpgradeRule name(String nameFragment) {
        return new VoidUpgradeRule(Type.NAME, nameFragment, null);
    }

    public static VoidUpgradeRule equipment(EquipmentSlot slot) {
        return new VoidUpgradeRule(Type.EQUIPMENT, slot.getName(), null);
    }

    public boolean matches(ItemStack stack, boolean matchNbt) {
        return matches(stack, matchNbt, matchNbt, matchNbt);
    }

    public boolean matches(ItemStack stack, boolean matchNbt, boolean matchDamage,
                           boolean matchEnchantments) {
        if (stack.isEmpty()) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) return false;
        return switch (type) {
            case ITEM -> value.equals(itemId.toString())
                    && itemNbtMatches(itemNbt, stack.getTag(), matchNbt, matchDamage,
                    matchEnchantments);
            case TAG -> {
                ResourceLocation id = ResourceLocation.tryParse(value);
                yield id != null && stack.is(TagKey.create(
                        Registries.ITEM, id));
            }
            case MOD -> value.equals(itemId.getNamespace());
            case NAME -> stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(value);
            case EQUIPMENT -> stack.getItem() instanceof ArmorItem armor
                    && armor.getEquipmentSlot().getName().equals(value);
        };
    }

    static boolean itemNbtMatches(CompoundTag expected, CompoundTag actual, boolean matchNbt,
                                  boolean matchDamage, boolean matchEnchantments) {
        if (matchDamage && damage(expected) != damage(actual)) return false;
        if (matchEnchantments
                && (!enchantmentsEqual(expected, actual, "Enchantments")
                || !enchantmentsEqual(expected, actual, "StoredEnchantments"))) return false;
        if (!matchNbt) return true;
        return ordinaryNbtEquals(expected, actual);
    }

    private static int damage(CompoundTag tag) {
        return tag == null ? 0 : tag.getInt("Damage");
    }

    private static boolean enchantmentsEqual(CompoundTag expected, CompoundTag actual,
                                             String key) {
        ListTag left = list(expected, key);
        ListTag right = list(actual, key);
        if (left.size() != right.size()) return false;
        boolean[] matched = new boolean[right.size()];
        for (int i = 0; i < left.size(); i++) {
            boolean found = false;
            for (int j = 0; j < right.size(); j++) {
                if (!matched[j] && left.get(i).equals(right.get(j))) {
                    matched[j] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static ListTag list(CompoundTag tag, String key) {
        if (tag != null && tag.get(key) instanceof ListTag list) return list;
        return new ListTag();
    }

    private static boolean ordinaryNbtEquals(CompoundTag expected, CompoundTag actual) {
        if (expected != null) {
            for (String key : expected.getAllKeys()) {
                if (!isSeparateOption(key)
                        && (actual == null || !Objects.equals(expected.get(key), actual.get(key)))) {
                    return false;
                }
            }
        }
        if (actual != null) {
            for (String key : actual.getAllKeys()) {
                if (!isSeparateOption(key) && (expected == null || !expected.contains(key))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSeparateOption(String key) {
        return key.equals("Damage") || key.equals("Enchantments")
                || key.equals("StoredEnchantments");
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", type.name());
        tag.putString("Value", value);
        if (itemNbt != null) tag.put("ItemNbt", itemNbt.copy());
        return tag;
    }

    public static VoidUpgradeRule fromTag(CompoundTag tag) {
        try {
            Type type = Type.valueOf(tag.getString("Type"));
            String value = tag.getString("Value");
            if (!isValid(type, value)) return null;
            CompoundTag itemNbt = tag.contains("ItemNbt", CompoundTag.TAG_COMPOUND)
                    ? tag.getCompound("ItemNbt") : null;
            return new VoidUpgradeRule(type, value, itemNbt);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isValid(Type type, String value) {
        if (value == null || value.isBlank() || value.length() > 128) return false;
        return switch (type) {
            case ITEM, TAG -> ResourceLocation.tryParse(value) != null;
            case MOD -> value.matches("[a-z0-9_.-]{1,64}");
            case NAME -> value.codePoints().noneMatch(Character::isISOControl);
            case EQUIPMENT -> value.equals("head") || value.equals("chest")
                    || value.equals("legs") || value.equals("feet");
        };
    }
}
