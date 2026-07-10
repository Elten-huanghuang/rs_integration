package com.huanghuang.rsintegration.resonance.passive;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class PassiveRegistry {

    private static final Set<Item> ITEMS = new HashSet<>();

    private PassiveRegistry() {}

    public static boolean isPassiveItem(ItemStack stack) {
        return !stack.isEmpty() && ITEMS.contains(stack.getItem());
    }

    public static void register(Item item) {
        ITEMS.add(item);
    }

    public static Set<Item> getRegisteredItems() {
        return Collections.unmodifiableSet(ITEMS);
    }

    /** Scan all registered items for attribute modifiers — Phase 1 zero-config population. */
    public static void scanAllItems() {
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (!stack.getAttributeModifiers(slot).isEmpty()) {
                    ITEMS.add(item);
                    break;
                }
            }
        }
    }

    public static void clear() {
        ITEMS.clear();
    }
}
