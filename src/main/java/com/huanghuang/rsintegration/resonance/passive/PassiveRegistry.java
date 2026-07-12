package com.huanghuang.rsintegration.resonance.passive;

import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.Set;

public final class PassiveRegistry {

    private static final Set<Item> ITEMS = new HashSet<>();

    private PassiveRegistry() {}

    public static void register(Item item) {
        ITEMS.add(item);
    }

    public static void clear() {
        ITEMS.clear();
    }
}
