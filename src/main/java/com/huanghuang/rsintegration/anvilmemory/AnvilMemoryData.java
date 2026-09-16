package com.huanghuang.rsintegration.anvilmemory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class AnvilMemoryData {
    public static final int LIMIT = 6;
    private static final String ROOT = "RSIntegrationAnvilMemory";
    private static final String LOCKED = "RSIntegrationLocked";

    private AnvilMemoryData() {}

    public record MemoryEntry(ItemStack stack, boolean locked) {
        public MemoryEntry {
            stack = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        }
    }

    public static List<ItemStack> get(ServerPlayer player, String adapterId) {
        return getEntries(player, adapterId).stream().map(MemoryEntry::stack).toList();
    }

    public static List<MemoryEntry> getEntries(ServerPlayer player, String adapterId) {
        ListTag stored = root(player).getList(adapterId, Tag.TAG_COMPOUND);
        List<MemoryEntry> result = new ArrayList<>(Math.min(stored.size(), LIMIT));
        for (int i = 0; i < stored.size() && result.size() < LIMIT; i++) {
            CompoundTag tag = stored.getCompound(i);
            ItemStack stack = ItemStack.of(tag);
            if (!stack.isEmpty()) result.add(new MemoryEntry(stack, tag.getBoolean(LOCKED)));
        }
        return normalize(result);
    }

    public static void remember(ServerPlayer player, String adapterId, ItemStack material, boolean rememberNbt) {
        if (material.isEmpty()) return;
        List<MemoryEntry> values = updateEntries(getEntries(player, adapterId), material, rememberNbt);
        save(player, adapterId, values);
    }

    public static boolean toggleLocked(ServerPlayer player, String adapterId, int index) {
        List<MemoryEntry> values = new ArrayList<>(getEntries(player, adapterId));
        if (index < 0 || index >= values.size()) return false;

        MemoryEntry target = values.remove(index);
        int lockedCount = (int) values.stream().filter(MemoryEntry::locked).count();
        values.add(lockedCount, new MemoryEntry(target.stack(), !target.locked()));
        save(player, adapterId, values);
        return true;
    }

    static List<ItemStack> update(List<ItemStack> existing, ItemStack material, boolean rememberNbt) {
        if (material.isEmpty()) return List.copyOf(existing);
        ItemStack remembered = material.copyWithCount(1);
        if (!rememberNbt) remembered.setTag(null);
        List<ItemStack> values = new ArrayList<>(existing);
        values.removeIf(stack -> rememberNbt
                ? ItemStack.isSameItemSameTags(stack, remembered)
                : ItemStack.isSameItem(stack, remembered));
        values.add(0, remembered);
        if (values.size() > LIMIT) values.subList(LIMIT, values.size()).clear();
        return List.copyOf(values);
    }

    static List<MemoryEntry> updateEntries(List<MemoryEntry> existing, ItemStack material,
                                           boolean rememberNbt) {
        List<MemoryEntry> values = new ArrayList<>(normalize(existing));
        if (material.isEmpty()) return List.copyOf(values);

        ItemStack remembered = material.copyWithCount(1);
        if (!rememberNbt) remembered.setTag(null);

        boolean lockedMatch = values.stream().anyMatch(entry ->
                entry.locked() && (rememberNbt
                        ? ItemStack.isSameItemSameTags(entry.stack(), remembered)
                        : ItemStack.isSameItem(entry.stack(), remembered)));
        values.removeIf(entry -> !entry.locked() && (rememberNbt
                ? ItemStack.isSameItemSameTags(entry.stack(), remembered)
                : ItemStack.isSameItem(entry.stack(), remembered)));
        if (lockedMatch) return List.copyOf(values);

        int lockedCount = (int) values.stream().filter(MemoryEntry::locked).count();
        if (lockedCount < LIMIT) values.add(lockedCount, new MemoryEntry(remembered, false));
        if (values.size() > LIMIT) values.remove(values.size() - 1);
        return normalize(values);
    }

    private static List<MemoryEntry> normalize(List<MemoryEntry> entries) {
        List<MemoryEntry> locked = new ArrayList<>();
        List<MemoryEntry> unlocked = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            if (entry == null || entry.stack().isEmpty()) continue;
            (entry.locked() ? locked : unlocked).add(new MemoryEntry(entry.stack(), entry.locked()));
        }
        List<MemoryEntry> result = new ArrayList<>(Math.min(LIMIT, locked.size() + unlocked.size()));
        result.addAll(locked);
        result.addAll(unlocked);
        if (result.size() > LIMIT) result.subList(LIMIT, result.size()).clear();
        return List.copyOf(result);
    }

    private static void save(ServerPlayer player, String adapterId, List<MemoryEntry> values) {
        ListTag list = new ListTag();
        for (MemoryEntry entry : normalize(values)) {
            CompoundTag tag = entry.stack().save(new CompoundTag());
            if (entry.locked()) tag.putBoolean(LOCKED, true);
            list.add(tag);
        }
        root(player).put(adapterId, list);
    }

    private static CompoundTag root(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);
        player.getPersistentData().put(ServerPlayer.PERSISTED_NBT_TAG, persisted);
        if (!persisted.contains(ROOT, Tag.TAG_COMPOUND)) persisted.put(ROOT, new CompoundTag());
        return persisted.getCompound(ROOT);
    }
}
