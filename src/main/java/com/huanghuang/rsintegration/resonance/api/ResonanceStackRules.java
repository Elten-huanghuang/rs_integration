package com.huanghuang.rsintegration.resonance.api;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/** Shared logical-slot and exact-variant rules for every resonance backend. */
public final class ResonanceStackRules {
    private static final String SLOT_TAG = "RSISlot";

    private ResonanceStackRules() {}

    public static boolean isLogicallyNonStackable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getMaxStackSize() <= 1 || stack.isDamageableItem()) return true;
        if (stack.getTag() != null && stack.getTag().getAllKeys().stream()
                .anyMatch(key -> !SLOT_TAG.equals(key))) return true;
        try {
            var modifiers = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
            if (modifiers.containsKey(Attributes.ATTACK_DAMAGE)
                    || modifiers.containsKey(Attributes.ATTACK_SPEED)) return true;
        } catch (RuntimeException ignored) {
            // Broken third-party attributes must not block disk access.
        }
        for (Class<?> type = stack.getItem().getClass(); type != null; type = type.getSuperclass()) {
            if ("mods.flammpfeil.slashblade.item.ItemSlashBlade".equals(type.getName())) return true;
        }
        return false;
    }

    public static boolean isSameVariant(ItemStack first, ItemStack second) {
        return !first.isEmpty() && !second.isEmpty()
                && ItemStack.isSameItemSameTags(first, second);
    }
}
