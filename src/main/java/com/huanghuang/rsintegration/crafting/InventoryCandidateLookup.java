package com.huanghuang.rsintegration.crafting;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class InventoryCandidateLookup {
    private final Map<CraftingResolver.StackKey, Integer> available;
    private final boolean useIndex;
    private Map<Item, List<OrderedKey>> byItem;

    InventoryCandidateLookup(Map<CraftingResolver.StackKey, Integer> available) {
        this(available, true);
    }

    InventoryCandidateLookup(Map<CraftingResolver.StackKey, Integer> available, boolean useIndex) {
        this.available = available;
        this.useIndex = useIndex;
    }

    Collection<CraftingResolver.StackKey> allKeys() {
        return available.keySet();
    }

    int count(CraftingResolver.StackKey key) {
        return available.getOrDefault(key, 0);
    }

    Collection<CraftingResolver.StackKey> keysFor(Ingredient ingredient) {
        if (!useIndex || !IngredientMatcher.hasCompleteItemList(ingredient)) return allKeys();
        if (byItem == null) {
            byItem = new HashMap<>();
            int ordinal = 0;
            for (CraftingResolver.StackKey key : available.keySet()) {
                byItem.computeIfAbsent(key.item(), ignored -> new ArrayList<>())
                        .add(new OrderedKey(key, ordinal++));
            }
        }
        List<OrderedKey> selected = new ArrayList<>();
        Set<Item> checkedItems = new HashSet<>();
        for (ItemStack template : ingredient.getItems()) {
            if (template.isEmpty() || !checkedItems.add(template.getItem())) continue;
            selected.addAll(byItem.getOrDefault(template.getItem(), List.of()));
        }
        selected.sort(Comparator.comparingInt(OrderedKey::ordinal));
        return selected.stream().map(OrderedKey::key).toList();
    }

    private record OrderedKey(CraftingResolver.StackKey key, int ordinal) {}
}
