package com.huanghuang.rsintegration.voidupgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

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
        if (stack.isEmpty()) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) return false;
        return switch (type) {
            case ITEM -> value.equals(itemId.toString())
                    && (!matchNbt || Objects.equals(itemNbt, stack.getTag()));
            case TAG -> {
                ResourceLocation id = ResourceLocation.tryParse(value);
                yield id != null && stack.is(net.minecraft.tags.TagKey.create(
                        net.minecraft.core.registries.Registries.ITEM, id));
            }
            case MOD -> value.equals(itemId.getNamespace());
            case NAME -> stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(value);
            case EQUIPMENT -> stack.getItem() instanceof ArmorItem armor
                    && armor.getEquipmentSlot().getName().equals(value);
        };
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
