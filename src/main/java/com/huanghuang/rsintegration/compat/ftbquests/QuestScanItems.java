package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StoredItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Immutable item/count snapshots shared by storage and player-inventory task scans. */
final class QuestScanItems {

    private QuestScanItems() {
    }

    static List<Entry> fromStorage(StorageSnapshot snapshot) {
        List<Entry> items = new ArrayList<>(snapshot.items().size());
        for (StoredItem stored : snapshot.items()) {
            items.add(new Entry(stored.stack(), stored.amount()));
        }
        return List.copyOf(items);
    }

    static List<Entry> fromPlayer(ServerPlayer player, List<ItemStack> curiosStacks) {
        List<Entry> items = new ArrayList<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            add(items, inventory.getItem(slot));
        }
        for (ItemStack stack : curiosStacks) {
            add(items, stack);
        }
        return List.copyOf(items);
    }

    private static void add(List<Entry> items, ItemStack stack) {
        if (stack != null && !stack.isEmpty() && stack.getCount() > 0) {
            items.add(new Entry(stack, stack.getCount()));
        }
    }

    record Entry(ItemStack stack, long amount) {
        Entry {
            if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("empty scan item");
            if (amount <= 0) throw new IllegalArgumentException("scan amount must be positive");
            stack = stack.copyWithCount(1);
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }
}
