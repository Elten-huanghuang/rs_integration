package com.huanghuang.rsintegration.storage.rs;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

record RefinedStorageSnapshotRead(boolean available, List<ItemStack> items) {
    RefinedStorageSnapshotRead {
        Objects.requireNonNull(items, "items");
        List<ItemStack> copied = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            copied.add(Objects.requireNonNull(item, "item").copy());
        }
        items = List.copyOf(copied);
        if (!available && !items.isEmpty()) {
            throw new IllegalArgumentException("unavailable snapshot cannot contain items");
        }
    }

    static RefinedStorageSnapshotRead available(List<ItemStack> items) {
        return new RefinedStorageSnapshotRead(true, items);
    }

    static RefinedStorageSnapshotRead unavailable() {
        return new RefinedStorageSnapshotRead(false, List.of());
    }

    @Override
    public List<ItemStack> items() {
        return items.stream().map(ItemStack::copy).toList();
    }
}
